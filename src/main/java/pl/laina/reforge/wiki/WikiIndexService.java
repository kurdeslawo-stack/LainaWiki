package pl.laina.reforge.wiki;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A cached, read-only index of pages in the Wiki's Przedmioty category. */
public final class WikiIndexService implements AutoCloseable {
    private static final int MAX_BATCHES = 20;
    private final LainaWikiPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Path cacheFile;
    private final String endpoint;
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private org.bukkit.scheduler.BukkitTask task;
    private int consecutiveFailures;
    private volatile Map<String, String> titles = Map.of();

    public WikiIndexService(LainaWikiPlugin plugin) {
        this.plugin = plugin;
        this.cacheFile = plugin.getDataFolder().toPath().resolve("wiki-index.txt");
        this.endpoint = plugin.getConfig().getString("wiki.api-endpoint", "https://wiki.laina.pl/api.php");
    }

    private void readSavedIndex() {
        try {
            if (Files.isRegularFile(cacheFile)) publish(Files.readAllLines(cacheFile, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.getLogger().warning("Wiki: nie udalo sie odczytac zapisanego indeksu: " + exception.getMessage());
        }
    }

    public synchronized void start() {
        if (!closed && task == null) schedule(20L);
    }

    private synchronized void schedule(long ticks) {
        if (closed || !plugin.isEnabled()) return;
        try {
            task = plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, this::refresh, ticks);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }

    static long nextRefreshMinutes(long configuredMinutes, int failures) {
        long normal = Math.clamp(configuredMinutes, 1L, 10080L);
        return failures <= 0 ? normal : Math.min(normal, Math.min(30L, 1L << Math.min(failures - 1, 5)));
    }

    public Optional<String> exact(String title) {
        return Optional.ofNullable(titles.get(WikiCatalog.titleKey(title)));
    }

    public Optional<String> search(String query) {
        String needle = WikiCatalog.normalize(query);
        if (needle.isBlank()) return Optional.empty();
        String exact = titles.get(WikiCatalog.titleKey(query));
        if (exact != null) return Optional.of(exact);
        return titles.entrySet().stream().filter(entry -> WikiCatalog.normalize(entry.getValue()).startsWith(needle))
                .map(Map.Entry::getValue).findFirst()
                .or(() -> titles.entrySet().stream().filter(entry -> WikiCatalog.normalize(entry.getValue()).contains(needle))
                        .map(Map.Entry::getValue).findFirst());
    }

    public List<String> unmapped(WikiCatalog catalog) {
        return titles.values().stream().filter(title -> !catalog.containsTitle(title)).toList();
    }

    public List<String> suggestions(String query, int limit) {
        return WikiSearch.suggestions(titles.values(), query, limit);
    }

    public int size() {
        return titles.size();
    }

    public List<String> titles() {
        return List.copyOf(titles.values());
    }

    private void refresh() {
        if (closed || !refreshing.compareAndSet(false, true)) return;
        try {
            if (titles.isEmpty()) readSavedIndex();
            List<String> fetched = fetchAll();
            if (closed) return;
            if (fetched.isEmpty()) throw new IllegalStateException("Wiki zwrocila pusty indeks");
            Files.createDirectories(cacheFile.getParent());
            Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.write(temporary, fetched, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
            publish(fetched);
            consecutiveFailures = 0;
            plugin.getLogger().info("Wiki: zsynchronizowano " + titles.size() + " stron przedmiotow.");
        } catch (Exception exception) {
            consecutiveFailures = Math.min(30, consecutiveFailures + 1);
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!closed) plugin.getLogger().warning("Wiki: synchronizacja nieudana, zachowano poprzedni indeks: " + exception.getMessage());
        } finally {
            refreshing.set(false);
            schedule(nextRefreshMinutes(plugin.getConfig().getLong("wiki.index-refresh-minutes", 360), consecutiveFailures) * 1200L);
        }
    }

    private List<String> fetchAll() throws Exception {
        List<String> result = new ArrayList<>();
        String continuation = null;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            if (closed) throw new InterruptedException("Wiki closed");
            String query = "action=query&list=categorymembers&cmtitle="
                    + URLEncoder.encode("Kategoria:Przedmioty", StandardCharsets.UTF_8)
                    + "&cmtype=page&cmlimit=500&format=xml";
            if (continuation != null) query += "&cmcontinue=" + URLEncoder.encode(continuation, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                    .timeout(Duration.ofSeconds(12)).header("User-Agent", "LainaWiki/0.1 WikiIndex")
                    .GET().build();
            HttpResponse<byte[]> response = client.send(request, WikiHttp.limitedBody());
            if (response.statusCode() != 200) throw new IllegalStateException("Wiki HTTP " + response.statusCode());
            var document = WikiHttp.parseXml(response.body());
            if (document.getElementsByTagName("error").getLength() > 0) {
                throw new IllegalStateException("Wiki zwróciła błąd API; zachowano pełny poprzedni indeks");
            }
            NodeList members = document.getElementsByTagName("cm");
            for (int index = 0; index < members.getLength(); index++) {
                Element member = (Element) members.item(index);
                if ("0".equals(member.getAttribute("ns"))) result.add(member.getAttribute("title"));
            }
            NodeList more = document.getElementsByTagName("continue");
            if (more.getLength() == 0) return result;
            continuation = ((Element) more.item(0)).getAttribute("cmcontinue");
            if (continuation.isBlank()) return result;
        }
        throw new IllegalStateException("Wiki ma wiecej stron niz limit synchronizacji");
    }

    private void publish(List<String> source) {
        Map<String, String> indexed = new LinkedHashMap<>();
        for (String title : source) {
            String normalized = WikiCatalog.titleKey(title);
            if (!normalized.isBlank()) indexed.putIfAbsent(normalized, WikiCatalog.displayTitle(title));
        }
        titles = Collections.unmodifiableMap(indexed);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (task != null) task.cancel();
        client.shutdownNow();
    }
}
