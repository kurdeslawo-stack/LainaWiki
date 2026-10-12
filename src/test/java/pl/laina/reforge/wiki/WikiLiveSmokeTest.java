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
    @Test
    void liveArticlesPreserveSourceLinkRangesInDescriptionsAndAcquisitionViews() throws Exception {
        try (var service = new WikiArticleService("https://wiki.laina.pl/api.php",
                "https://wiki.laina.pl/", 7000, 30, 10)) {
            for (String title : java.util.List.of("Pisanka", "Lapisowy Kilof", "Cash Shop")) {
                var article = service.load(title).get(15, TimeUnit.SECONDS).value();
                assertFalse(article.content().links().isEmpty(), title);
                assertFalse(article.text().contains("<u>"), title);
                assertFalse(article.text().contains("<gallery"), title);
                if (title.equals("Cash Shop")) {
                    assertTrue(article.text().contains(WikiArticleService.GALLERY_NOTICE));
                    assertTrue(article.missingTitles().contains(WikiCatalog.normalize("Overworld")));
                    assertFalse(article.missingTitles().contains(WikiCatalog.normalize("Coin")));
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
}
