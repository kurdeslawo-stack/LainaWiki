package pl.laina.reforge.wiki;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCacheTest {
    @Test
    void repeatedColdFailuresBackOffThenRecover() throws Exception {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        try (var cache = new AsyncCache<String>(key -> {
            if (calls.incrementAndGet() == 1) throw new IOException("Offline");
            return "recovered";
        }, 1000, 2, time::get)) {
            assertThrows(ExecutionException.class, () -> cache.load("a").get(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> cache.load("a").get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            time.set(30_001);
            assertEquals("recovered", cache.load("a").get(3, TimeUnit.SECONDS).value());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void staleFallbackStaysStaleDuringBackoffAndDoesNotRefetch() throws Exception {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        try (var cache = new AsyncCache<String>(key -> {
            if (calls.incrementAndGet() > 1) throw new IOException("Offline");
            return "good";
        }, 100, 2, time::get)) {
            cache.load("a").get(3, TimeUnit.SECONDS);
            time.set(101);
            assertTrue(cache.load("a").get(3, TimeUnit.SECONDS).stale());
            assertTrue(cache.load("a").get(3, TimeUnit.SECONDS).stale());
            assertEquals(2, calls.get());
            time.set(30_102);
            assertTrue(cache.load("a").get(3, TimeUnit.SECONDS).stale());
            assertEquals(3, calls.get());
        }
    }
    @Test
    void saturatedQueueRejectsInsteadOfRunningHttpOnCallerThread() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        try (var cache = new AsyncCache<String>(key -> {
            started.countDown();
            release.await();
            return key;
        }, 1000, 2, System::currentTimeMillis)) {
            for (int i = 0; i < 3; i++) cache.load("running-" + i);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) cache.load("queued-" + i);
            var overflow = cache.load("overflow");
            assertTrue(overflow.isCompletedExceptionally());
            var failure = assertThrows(ExecutionException.class, () -> overflow.get(1, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        } finally { release.countDown(); }
    }

    @Test
    void simultaneousReadersShareOneBackgroundRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Thread caller = Thread.currentThread();
        try (var cache = new AsyncCache<String>(key -> {
            assertNotSame(caller, Thread.currentThread());
            calls.incrementAndGet();
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test timed out");
            return "article";
        }, 1000, 3, System::currentTimeMillis)) {
            var first = cache.load("same");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertSame(first, cache.load("same"));
            release.countDown();
            assertEquals("article", first.get(3, TimeUnit.SECONDS).value());
            assertFalse(cache.load("same").get().stale());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void expiredArticleSurvivesNetworkFailureButIsMarkedStale() throws Exception {
        AtomicLong time = new AtomicLong(0);
        AtomicInteger calls = new AtomicInteger();
        try (var cache = new AsyncCache<String>(key -> {
            if (calls.incrementAndGet() > 1) throw new IOException("Offline");
            return "last good text";
        }, 100, 3, time::get)) {
            assertFalse(cache.load("a").get(3, TimeUnit.SECONDS).stale());
            time.set(101);
            var fallback = cache.load("a").get(3, TimeUnit.SECONDS);
            assertTrue(fallback.stale());
            assertEquals("last good text", fallback.value());
            assertThrows(ExecutionException.class, () -> cache.load("new").get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void capacityEvictsOldestUnusedEntry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var cache = new AsyncCache<String>(key -> { calls.incrementAndGet(); return key; },
                1000, 2, () -> 0L)) {
            cache.load("a").get(3, TimeUnit.SECONDS);
            cache.load("b").get(3, TimeUnit.SECONDS);
            cache.load("a").get(3, TimeUnit.SECONDS);
            cache.load("c").get(3, TimeUnit.SECONDS);
            cache.load("b").get(3, TimeUnit.SECONDS);
            assertEquals(4, calls.get());
        }
    }

    @Test
    void closeCancelsPendingWorkAndRejectsNewRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        var cache = new AsyncCache<String>(key -> {
            started.countDown();
            new CountDownLatch(1).await();
            return key;
        }, 1000, 2, System::currentTimeMillis);
        try {
            var pending = cache.load("a");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cache.close();
            assertTrue(pending.isCancelled());
            assertThrows(CancellationException.class, () -> cache.load("b").join());
        } finally { cache.close(); }
    }
}
