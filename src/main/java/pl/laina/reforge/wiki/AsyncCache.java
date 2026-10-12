package pl.laina.reforge.wiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded background loading, shared requests and last-known-good fallback. */
final class AsyncCache<T> implements AutoCloseable {
    interface Fetcher<T> { T fetch(String key) throws Exception; }
    record Result<T>(T value, boolean stale) {}
    private record Entry<T>(T value, long expires) {}
    private record Failure(Exception cause, long retryAt) {}
    private static final long FAILURE_BACKOFF_MILLIS = 30_000;
    private final Map<String, Entry<T>> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Failure> failures = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Result<T>>> pending = new HashMap<>();
    private final ThreadPoolExecutor executor;
    private final Fetcher<T> fetcher;
    private final LongSupplier clock;
    private final long ttl;
    private final int capacity;
    private boolean closed;

    AsyncCache(Fetcher<T> fetcher, long ttl, int capacity, LongSupplier clock) {
        this.fetcher = fetcher;
        this.ttl = ttl;
        this.capacity = Math.max(1, capacity);
        this.clock = clock;
        executor = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "LainaWiki-article");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized CompletableFuture<Result<T>> load(String key) {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Wiki closed"));
        Entry<T> previous = cache.get(key);
        if (previous != null && previous.expires > clock.getAsLong()) {
            return CompletableFuture.completedFuture(new Result<>(previous.value, false));
        }
        if (pending.containsKey(key)) return pending.get(key);
        Failure recentFailure = failures.get(key);
        if (recentFailure != null && recentFailure.retryAt > clock.getAsLong()) {
            return previous == null ? CompletableFuture.failedFuture(recentFailure.cause)
                    : CompletableFuture.completedFuture(new Result<>(previous.value, true));
        }
        failures.remove(key);
        CompletableFuture<Result<T>> future = new CompletableFuture<>();
        pending.put(key, future);
        try {
            executor.execute(() -> {
                Result<T> result = null;
                Exception failure = null;
                try {
                    T value = fetcher.fetch(key);
                    synchronized (this) {
                        if (!closed) {
                            failures.remove(key);
                            cache.put(key, new Entry<>(value, clock.getAsLong() + ttl));
                            while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
                        }
                    }
                    result = new Result<>(value, false);
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    failure = exception;
                    synchronized (this) {
                        if (!closed) {
                            failures.put(key, new Failure(exception, clock.getAsLong() + FAILURE_BACKOFF_MILLIS));
                            while (failures.size() > capacity) failures.remove(failures.keySet().iterator().next());
                        }
                    }
                    if (previous != null) result = new Result<>(previous.value, true);
                } finally {
                    synchronized (this) { pending.remove(key, future); }
                }
                if (result != null) future.complete(result);
                else future.completeExceptionally(failure);
            });
        } catch (RejectedExecutionException exception) {
            pending.remove(key);
            if (previous != null) future.complete(new Result<>(previous.value, true));
            else future.completeExceptionally(exception);
        }
        return future;
    }

    @Override
    public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        var unfinished = new ArrayList<>(pending.values());
        pending.clear();
        cache.clear();
        failures.clear();
        unfinished.forEach(future -> future.cancel(false));
    }
}
