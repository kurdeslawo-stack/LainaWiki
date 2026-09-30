package pl.laina.reforge.wiki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Serialized disk operations: a successful toggle means the file has been saved. */
final class WikiFavorites implements AutoCloseable {
    static final int LIMIT = 100;
    enum Change { ADDED, REMOVED, LIMIT_REACHED }
    private final Path directory;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "LainaWiki-favorites");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    WikiFavorites(Path directory) { this.directory = directory; }

    CompletableFuture<List<String>> list(UUID player) {
        return submit(() -> read(player));
    }

    CompletableFuture<Change> toggle(UUID player, String title) {
        return submit(() -> {
            if (title == null || title.isBlank() || title.length() > 512) throw new IOException("Niepoprawny tytuł");
            List<String> titles = new ArrayList<>(read(player));
            boolean removed = titles.removeIf(value -> sameTitle(value, title));
            if (!removed && titles.size() >= LIMIT) return Change.LIMIT_REACHED;
            if (!removed) titles.add(title.strip().replace('_', ' '));
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, player + "-", ".tmp");
            try {
                Files.write(temporary, titles.stream().map(value -> Base64.getEncoder()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8))).toList(), StandardCharsets.UTF_8);
                Path destination = directory.resolve(player + ".txt");
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
            return removed ? Change.REMOVED : Change.ADDED;
        });
    }

    private List<String> read(UUID player) throws IOException {
        Path file = directory.resolve(player + ".txt");
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > 300_000) throw new IOException("Plik ulubionych jest zbyt duży");
        List<String> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String title = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                if (title.isBlank() || title.length() > 512 || result.size() >= LIMIT) {
                    throw new IOException("Uszkodzony plik ulubionych");
                }
                result.add(title);
            }
        } catch (IllegalArgumentException invalid) { throw new IOException("Uszkodzony plik ulubionych", invalid); }
        return List.copyOf(result);
    }

    static boolean sameTitle(String left, String right) {
        return left.strip().replace('_', ' ').equalsIgnoreCase(right.strip().replace('_', ' '));
    }

    private <T> CompletableFuture<T> submit(Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try { result.complete(operation.call()); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException full) { result.completeExceptionally(full); }
        return result;
    }

    @Override public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Zapisy ulubionych nadal trwają; pliki nie zostały usunięte");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
