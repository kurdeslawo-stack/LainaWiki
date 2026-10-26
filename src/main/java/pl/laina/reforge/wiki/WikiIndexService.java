package pl.laina.reforge.wiki;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Cached public articles, with a separate item category for browsing. */
public final class WikiIndexService implements AutoCloseable {
    private final LainaWikiPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Path cacheFile;
    private final Path articleCacheFile;
    private final String endpoint;
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private org.bukkit.scheduler.BukkitTask task;
    private int consecutiveFailures;
    private record Index(Map<String, String> titles, java.util.Set<String> items) {}
    private volatile Index snapshot = new Index(Map.of(), java.util.Set.of());

    public WikiIndexService(LainaWikiPlugin plugin) {
        this.plugin = plugin;
        this.cacheFile = plugin.getDataFolder().toPath().resolve("wiki-index.txt");
        this.articleCacheFile = plugin.getDataFolder().toPath().resolve("wiki-articles.txt");
        this.endpoint = plugin.getConfig().getString("wiki.api-endpoint", "https://wiki.laina.pl/api.php");
    }

    private void readSavedIndex() {
        try {
            var items = Files.isRegularFile(cacheFile) ? Files.readAllLines(cacheFile, StandardCharsets.UTF_8) : List.<String>of();
            var articles = Files.isRegularFile(articleCacheFile) ? Files.readAllLines(articleCacheFile, StandardCharsets.UTF_8) : items;
            publish(new WikiPageIndex.Snapshot(articles, items));
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
        return Optional.ofNullable(snapshot.titles().get(WikiCatalog.titleKey(title)));
    }

    public Optional<String> search(String query) {
        String needle = WikiCatalog.normalize(query);
        if (needle.isBlank()) return Optional.empty();
        var titles = snapshot.titles();
        String exact = titles.get(WikiCatalog.titleKey(query));
        if (exact != null) return Optional.of(exact);
        return titles.entrySet().stream().filter(entry -> WikiCatalog.normalize(entry.getValue()).startsWith(needle))
                .map(Map.Entry::getValue).findFirst()
                .or(() -> titles.entrySet().stream().filter(entry -> WikiCatalog.normalize(entry.getValue()).contains(needle))
                        .map(Map.Entry::getValue).findFirst());
    }

    public List<String> unmapped(WikiCatalog catalog) {
        var current = snapshot;
        return current.titles().values().stream().filter(title -> current.items().contains(WikiCatalog.titleKey(title)) && !catalog.containsTitle(title)).toList();
    }
    public List<String> otherArticles(WikiCatalog catalog) {
        var current = snapshot;
        return current.titles().values().stream().filter(title -> !current.items().contains(WikiCatalog.titleKey(title)) && !catalog.containsTitle(title)).toList();
    }
    public List<String> allTitles(WikiCatalog catalog) {
        return mergedTitles(catalog.titles(), titles());
    }
    static List<String> mergedTitles(List<String> catalog, List<String> articles) {
        var unique = new LinkedHashMap<String,String>();
        java.util.stream.Stream.concat(catalog.stream(), articles.stream()).forEach(title ->
                unique.putIfAbsent(WikiCatalog.titleKey(title), WikiCatalog.displayTitle(title)));
        return List.copyOf(unique.values());
    }

    public List<String> suggestions(String query, int limit) {
        return WikiSearch.suggestions(snapshot.titles().values(), query, limit);
    }

    public int size() {
        return snapshot.titles().size();
    }

    public List<String> titles() {
        return List.copyOf(snapshot.titles().values());
    }

    private void refresh() {
        if (closed || !refreshing.compareAndSet(false, true)) return;
        try {
            if (snapshot.titles().isEmpty()) readSavedIndex();
            var fetched = WikiPageIndex.fetch(client, endpoint, () -> closed);
            if (closed) return;
            Files.createDirectories(cacheFile.getParent());
            saveIndex(cacheFile, fetched.items());
            saveIndex(articleCacheFile, fetched.articles());
            publish(fetched);
            consecutiveFailures = 0;
            plugin.getLogger().info("Wiki: zsynchronizowano " + size() + " artykułów, w tym " + snapshot.items().size() + " stron przedmiotów.");
        } catch (Exception exception) {
            consecutiveFailures = Math.min(30, consecutiveFailures + 1);
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!closed) plugin.getLogger().warning("Wiki: synchronizacja nieudana, zachowano poprzedni indeks: " + exception.getMessage());
        } finally {
            refreshing.set(false);
            schedule(nextRefreshMinutes(plugin.getConfig().getLong("wiki.index-refresh-minutes", 360), consecutiveFailures) * 1200L);
        }
    }

    private static void saveIndex(Path target, List<String> titles) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, titles, StandardCharsets.UTF_8);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private void publish(WikiPageIndex.Snapshot source) {
        Map<String, String> indexed = new LinkedHashMap<>();
        for (String title : mergedTitles(source.articles(), source.items())) {
            String normalized = WikiCatalog.titleKey(title);
            if (!normalized.isBlank()) indexed.putIfAbsent(normalized, WikiCatalog.displayTitle(title));
        }
        snapshot = new Index(Collections.unmodifiableMap(indexed), source.items().stream().map(WikiCatalog::titleKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (task != null) task.cancel();
        client.shutdownNow();
    }
}
