package pl.laina.reforge.wiki;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in check: ordinary builds must not depend on the external Wiki. */
@EnabledIfSystemProperty(named = "wiki.live-smoke", matches = "true")
class WikiLiveSmokeTest {
    @Test void dailyRewardsTableIsReadableAndKeepsLastDay() throws Exception {
        try (var service = new WikiArticleService("https://wiki.laina.pl/api.php",
                "https://wiki.laina.pl/", 12000, 30, 10)) {
            var article = service.load("Nagrody dzienne").get(15, TimeUnit.SECONDS).value();
            assertFalse(article.text().contains("wikitable"));
            assertFalse(article.text().contains("||"));
            assertTrue(article.text().contains("Dzień 1 — Szmaragd"));
            assertTrue(article.text().contains("Bez gildii: 16 · Gildia: 32"));
            assertTrue(article.text().contains("Dzień 22 — Netherowa gwiazda"));
            assertTrue(article.text().contains("Nagrody za 23 dzień"));
            assertTrue(article.content().links().stream().anyMatch(span -> span.link().targetTitle().equals("Księga Morałów")));
        }
    }
    @Test
    void liveArticlesPreserveSourceLinkRangesInDescriptionsAndAcquisitionViews() throws Exception {
        try (var service = new WikiArticleService("https://wiki.laina.pl/api.php",
                "https://wiki.laina.pl/", 7000, 30, 10)) {
            for (String title : java.util.List.of("Pisanka", "Lapisowy Kilof", "Cash Shop", "Ezoteryzm")) {
                var article = service.load(title).get(15, TimeUnit.SECONDS).value();
                assertFalse(article.content().links().isEmpty(), title);
                assertFalse(article.text().contains("<u>"), title);
                assertFalse(article.text().contains("<gallery"), title);
                if (title.equals("Cash Shop")) {
                    assertTrue(article.text().contains(WikiArticleService.GALLERY_NOTICE));
                    assertTrue(article.missingTitles().contains(WikiCatalog.titleKey("Overworld")));
                    assertFalse(article.missingTitles().contains(WikiCatalog.titleKey("Coin")));
                }
                for (var source : java.util.List.of(article.content(), article.acquisition())) {
                    if (source.text().isBlank()) continue;
                    for (var page : WikiDialogService.articleRichPages(source, 1250)) {
                        for (var block : WikiDialogService.richParts(page, WikiDialogService.articleBlocks(page.text()))) {
                            var rendered = WikiDialogService.linkedText(block, link -> ClickEvent.openUrl(link.url()));
                            assertEquals(block.text(), PlainTextComponentSerializer.plainText().serialize(rendered), title);
                            assertEquals(block.links().size(), rendered.children().stream()
                                    .filter(child -> child.clickEvent() != null).count(), title);
                        }
                    }
                }
                if (title.equals("Pisanka")) {
                    int plainIntro = article.text().indexOf("eventu wielkanocnego");
                    assertTrue(plainIntro >= 0);
                    assertTrue(article.content().links().stream().noneMatch(span ->
                            span.start() <= plainIntro && span.end() > plainIntro));
                    assertTrue(article.acquisition().links().stream().anyMatch(span ->
                            span.link().targetTitle().equals("Wiosenne Jajobranie")));
                }
            }
        }
    }
    @Test void liveIndexIncludesMechanicsAndKeepsItemsSeparate() throws Exception {
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var snapshot=WikiPageIndex.fetch(client,"https://wiki.laina.pl/api.php",()->false);
            assertTrue(snapshot.articles().contains("Ezoteryzm"));
            assertTrue(snapshot.articles().contains("Cash Shop"));
            assertFalse(snapshot.items().contains("Ezoteryzm"));
            assertTrue(snapshot.items().contains("Buty Erosa"));
            System.out.println("ALL_ARTICLES_LIVE articles="+snapshot.articles().size()+" items="+snapshot.items().size());
        }
    }
}
