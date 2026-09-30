package pl.laina.reforge.wiki;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WikiHttpTest {
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
