package pl.laina.reforge.wiki;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WikiHttpTest {
    @Test
    void requestsRedirectResolutionAndAcceptsCanonicalTitle() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.php", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            byte[] xml = "<api><parse title=\"Cel\"><wikitext>Opis celu</wikitext></parse></api>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            try (var out = exchange.getResponseBody()) { out.write(xml); }
        });
        server.start();
        try (var service = new WikiArticleService("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php",
                "https://wiki.laina.pl/", 7000, 30, 10)) {
            assertEquals("Cel", service.load("Alias").get(5, TimeUnit.SECONDS).value().title());
            assertTrue(query.get().contains("redirects=true"));
        } finally { server.stop(0); }
    }

    @Test
    void rejectsOversizedChunkedResponseBeforeXmlParsing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.php", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 300; i++) out.write(chunk);
            } catch (java.io.IOException cancelled) { /* Client is expected to cancel. */ }
        });
        server.start();
        try (var service = new WikiArticleService("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php",
                "https://wiki.laina.pl/", 7000, 30, 10)) {
            var failure = assertThrows(ExecutionException.class, () -> service.load("large").get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("limit"), failure.toString());
        } finally { server.stop(0); }
    }

    @Test
    void bodyLimitAcceptsExactBoundaryAndRejectsOverflow() throws Exception {
        var body = new WikiHttp.LimitedBody(4);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long count) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        body.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, body.getBody().toCompletableFuture().get());
        assertFalse(cancelled.get());
        assertThrows(Exception.class, () -> WikiHttp.parseXml("<!DOCTYPE api [<!ENTITY ext SYSTEM 'file:///not-readable'>]><api>&ext;</api>".getBytes(StandardCharsets.UTF_8)));
    }
    @Test
    void downloadsParsesAndCachesArticleThroughHttp() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.php", exchange -> {
            requests.incrementAndGet();
            byte[] xml = ("<api><parse title=\"Kryształ Empirii\"><wikitext>"
                    + "Opis [https://laina.pl sklep].\n== Jak zdobyć? ==\nZ [[Smok Endu]].\n"
                    + "== Autorzy ==\nAutor.</wikitext></parse></api>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            try (var out = exchange.getResponseBody()) { out.write(xml); }
        });
        server.start();
        try (var service = new WikiArticleService("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php",
                "https://wiki.laina.pl/", 7000, 30, 10)) {
            var result = service.load("Kryształ_Empirii").get(5, TimeUnit.SECONDS);
            assertEquals("Kryształ Empirii", result.value().title());
            assertTrue(result.value().text().startsWith("Opis sklep."));
            assertEquals("Z Smok Endu.", result.value().acquisitionText());
            var acquisitionLink = result.value().acquisition().links().getFirst();
            assertEquals(2, acquisitionLink.start());
            assertEquals("Smok Endu", result.value().acquisitionText().substring(acquisitionLink.start(), acquisitionLink.end()));
            assertEquals(2, result.value().content().links().size());
            assertTrue(result.value().links().stream().anyMatch(link -> link.url().equals("https://laina.pl")));
            assertFalse(result.stale());
            service.load("Kryształ Empirii").get(5, TimeUnit.SECONDS);
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    @Test
    void apiErrorsDoNotBecomeCachedArticles() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.php", exchange -> {
            byte[] xml = "<api><error info=\"No page\"/></api>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            try (var out = exchange.getResponseBody()) { out.write(xml); }
        });
        server.start();
        try (var service = new WikiArticleService("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php",
                "https://wiki.laina.pl/", 7000, 30, 10)) {
            var failure = assertThrows(ExecutionException.class,
                    () -> service.load("missing").get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        } finally { server.stop(0); }
    }
}
