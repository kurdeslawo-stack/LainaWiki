package pl.laina.reforge.wiki;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiItemInsightTest {
    @Test void liveErosSummaryIncludesDocumentedHitPoints() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("wiki.live-smoke"));
        try (var service = new WikiArticleService("https://wiki.laina.pl/api.php", "https://wiki.laina.pl/", 7000, 30, 256)) {
            var article = service.load("Buty Erosa").orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).join();
            var answer = WikiItemInsight.describe(article.value(), false, article.stale());
            assertEquals("Buty Erosa", answer.get("title"));
            assertTrue(answer.get("summary").contains("2 punkty HP"));
            assertFalse(answer.get("summary").contains("Autorem grafiki"));
            System.out.println("COMPANION_WIKI_LIVE " + answer.get("summary"));
        }
    }
    private WikiArticleService.WikiArticle article(String content, String acquisition) {
        return new WikiArticleService.WikiArticle("Buty_Erosa", new WikiRichText(content, List.of()),
                List.of(), new WikiRichText(acquisition, List.of()));
    }
    @Test void answersUseArticleIntroductionWithoutAuthorSection() {
        var answer = WikiItemInsight.describe(article("Specjalne buty.\n\n== Autorzy ==\nAutorem jest Test.\n\n== Opis ==\n2 HP.", "Cash Shop"), false, false);
        assertEquals("Buty Erosa", answer.get("title"));
        assertEquals("Specjalne buty. 2 HP.", answer.get("summary"));
        assertFalse(answer.get("summary").contains("Test"));
    }
    @Test void acquisitionNeverInventsMissingDropOrRecipe() {
        var answer = WikiItemInsight.describe(article("Specjalne buty.", ""), true, false);
        assertTrue(answer.get("summary").contains("Wiki nie podaje"));
        assertEquals("Cash Shop", WikiItemInsight.describe(article("Opis", "Cash Shop"), true, true).get("summary"));
        assertEquals("true", WikiItemInsight.describe(article("Opis", "Cash Shop"), true, true).get("stale"));
    }
    @Test void excerptPreservesUnicodeAndStaysBounded() {
        String excerpt = WikiItemInsight.excerpt("😀".repeat(300), 240);
        assertEquals(240, excerpt.codePointCount(0, excerpt.length()));
        assertTrue(excerpt.endsWith("…"));
        assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 2)));
    }
}
