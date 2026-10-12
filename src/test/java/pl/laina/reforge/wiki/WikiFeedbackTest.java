package pl.laina.reforge.wiki;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WikiFeedbackTest {
    @org.junit.jupiter.api.Test
    void emojiPresentationSelectorsAreRemovedWithoutBreakingLinks() {
        var text = WikiArticleService.parseDialogText(
                "Zapewnia 2 punkty HP (1❤️). [[Erosa|❤&#xFE0F; Erosa]] ☀\uFE0E", "https://wiki.laina.pl/");
        assertFalse(text.text().contains("\uFE0F"));
        assertFalse(text.text().contains("\uFE0E"));
        assertTrue(text.text().contains("(1❤)"));
        var span = text.links().getFirst();
        assertEquals("❤ Erosa", text.text().substring(span.start(), span.end()));
        assertEquals("Erosa", span.link().targetTitle());
    }
    @Test void cashShopPipeSeparatedExternalLinkProducesValidUrl() {
        var parsed = WikiArticleService.parseDialogText("[http://sklep.laina.pl|naszym sklepie internetowym]", "https://wiki.laina.pl/");
        assertEquals("naszym sklepie internetowym", parsed.text());
        assertEquals("http://sklep.laina.pl", parsed.links().getFirst().link().url());
        assertDoesNotThrow(() -> WikiDialogService.linkedText(parsed, link -> ClickEvent.openUrl(link.url())));
    }
    @Test void galleryProducesUniqueTextLinksAndNotice() {
        String source = "<gallery mode=\"packed\">\nchip.png|link=Żeton|[[Żeton]]\nkey.png|link=Klucz|[[Klucz|Klucz Żaboci]]\nkey2.png|[[Klucz|Klucz Żaboci]]\n</gallery>";
        var parsed = WikiArticleService.parseDialogText(source, "https://wiki.laina.pl/");
        assertTrue(parsed.text().contains(WikiArticleService.GALLERY_NOTICE));
        assertEquals(2, parsed.links().size());
        assertEquals(2, WikiArticleService.extractLinks(source, "https://wiki.laina.pl/").size());
        assertFalse(parsed.text().contains(".png"));
        assertFalse(parsed.text().contains("link="));
        assertFalse(parsed.text().contains("gallery"));
    }
    @Test void galleryWithoutLinksStillExplainsWhereToViewImages() {
        assertEquals(WikiArticleService.GALLERY_NOTICE,
                WikiArticleService.toPlainText("<gallery>cash_shop.png|Cash Shop</gallery>"));
    }
    @Test void stripsUnderlineTagsWithoutDamagingLinks() {
        var parsed = WikiArticleService.parseDialogText("Jeśli jesteś <u>[[Gnom|Gnomem]]</u>, <u>Atak</u>.", "https://wiki.laina.pl/");
        assertEquals("Jeśli jesteś Gnomem, Atak.", parsed.text());
        assertEquals("Gnom", parsed.links().getFirst().link().targetTitle());
    }
    @Test void paletteAcceptsRgbAndFallsBackForInvalidValue() {
        var config = new YamlConfiguration();
        config.set("wiki.colors.link", "#123456");
        config.set("wiki.colors.text", "oops");
        var palette = new WikiPalette(config, null);
        assertEquals(TextColor.color(0x123456), palette.color("link", NamedTextColor.AQUA));
        assertEquals(NamedTextColor.WHITE, palette.color("text", NamedTextColor.WHITE));
    }
    @Test void missingLinkUsesRedAndExplanatoryHover() {
        var source = WikiArticleService.parseDialogText("[[Brak]] i [[Cel]]", "https://wiki.laina.pl/");
        var rendered = WikiDialogService.linkedText(source, link -> ClickEvent.openUrl(link.url()),
                java.util.Set.of(WikiCatalog.normalize("Brak")), WikiPalette.defaults());
        var first = rendered.children().getFirst();
        assertEquals(NamedTextColor.RED, first.color());
        assertNotNull(first.hoverEvent());
        assertEquals(WikiDialogService.LINK_COLOR, rendered.children().getLast().color());
    }
    @Test void fetchesLinkExistenceInSameRequestAndSeparatesMissingArticleErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.php", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query.contains("prop=wikitext|links"));
            String response = query.contains("page=Missing")
                    ? "<api><error code=\"missingtitle\" info=\"missing\"/></api>"
                    : "<api><parse title=\"Cash Shop\"><links><pl ns=\"0\" exists=\"\">Cel</pl><pl ns=\"0\">Brak</pl></links><wikitext>[[Cel]] [[Brak]]</wikitext></parse></api>";
            byte[] xml = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            try (var out = exchange.getResponseBody()) { out.write(xml); }
        });
        server.start();
        try (var service = new WikiArticleService("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php", "https://wiki.laina.pl/", 7000, 30, 10)) {
            var article = service.load("Cash Shop").get(5, TimeUnit.SECONDS).value();
            assertEquals(java.util.Set.of(WikiCatalog.normalize("Brak")), article.missingTitles());
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> service.load("Missing").get(5, TimeUnit.SECONDS));
            assertTrue(WikiArticleService.isMissingArticle(error));
            assertFalse(WikiArticleService.isMissingArticle(new java.io.IOException("timeout")));
        } finally { server.stop(0); }
    }
}
