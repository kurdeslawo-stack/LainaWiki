package pl.laina.reforge.wiki;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiSearchTest {
    @Test void broadQueryKeepsAllMatchesAndRanksExactFirst() {
        var found = WikiSearch.find("miecz", List.of("Złoty Miecz", "Miecz Świetlny", "Miecz", "Łuk"));
        assertEquals(List.of("Miecz", "Miecz Świetlny", "Złoty Miecz"), found.titles());
        assertFalse(found.approximate());
    }
    @Test void matchesPolishNamesSeparatorsAndReorderedWords() {
        assertEquals(List.of("Boski Samurajski Łuk"), WikiSearch.find("luk boski", List.of("Boski_Samurajski_Łuk")).titles());
        assertEquals(List.of("Luk", "Łuk"), WikiSearch.find("luk", List.of("Łuk", "Luk", "łuk")).titles());
    }
    @Test void typoProducesSuggestionsInsteadOfSilentlyOpeningOne() {
        var found = WikiSearch.find("lapisowy kilofg", List.of("Lapisowy Kilof", "Diamentowy Kilof"));
        assertEquals(List.of("Lapisowy Kilof"), found.titles());
        assertTrue(found.approximate());
        assertTrue(WikiSearch.find("xyz", List.of("Łuk")).titles().isEmpty());
    }
    @Test void emptyAndOversizedQueriesDoNotMatchEverything() {
        for (String query : List.of("", " ", "!!!", "a".repeat(129))) {
            assertTrue(WikiSearch.find(query, List.of("Ametystowa Laska")).titles().isEmpty());
        }
    }
    @Test void tabCompletesOnlyRemainingWords() {
        assertEquals("Miecz Świetlny", WikiCommand.completionSuffix("Czerwony_Miecz_Świetlny", new String[]{"Czerwony", "Mi"}));
        assertEquals("Świetlny", WikiCommand.completionSuffix("Czerwony Miecz Świetlny", new String[]{"Czerwony", "Miecz", ""}));
        assertEquals("", WikiCommand.completionSuffix("Czerwony Miecz", new String[]{"Zielony", "Mi"}));
        assertEquals("Czerwony Miecz", WikiCommand.completionSuffix("Czerwony_Miecz", new String[]{"Cz"}));
    }
    @Test void counterAndRandomPoolUseUniqueTitlesRatherThanModelVariants() {
        var catalog = new WikiCatalog(Map.of(), List.of(WikiEntry.wikiOnly("Łuk"), WikiEntry.wikiOnly("łuk"), WikiEntry.wikiOnly("Miecz")));
        assertEquals(3, catalog.mappingCount());
        assertEquals(2, catalog.size());
        assertEquals(List.of("Łuk", "Miecz"), catalog.titles());
        for (int i = 0; i < 20; i++) assertTrue(catalog.titles().contains(catalog.random().orElseThrow().title()));
    }
    @Test void refreshBackoffRetriesQuicklyButNeverFasterThanOneMinute() {
        assertEquals(360, WikiIndexService.nextRefreshMinutes(360, 0));
        assertEquals(1, WikiIndexService.nextRefreshMinutes(360, 1));
        assertEquals(2, WikiIndexService.nextRefreshMinutes(360, 2));
        assertEquals(30, WikiIndexService.nextRefreshMinutes(360, 30));
        assertEquals(1, WikiIndexService.nextRefreshMinutes(0, 4));
        assertEquals(10, WikiIndexService.nextRefreshMinutes(10, 6));
    }
    @Test void webSearchEscapesQueryAndRespectsConfiguredWiki() {
        assertEquals("https://wiki.example/", WikiUrls.home("https://wiki.example/index.php?title={title}"));
        assertEquals("https://wiki.example/index.php?title=Special%3ASearch&search=%C5%82uk+%26+miecz",
                WikiUrls.search("https://wiki.example/", "łuk & miecz"));
    }

    @Test void tabSuggestionsDeduplicateBothSourcesBeforeApplyingLimit() {
        assertEquals(List.of("Buty Erosa", "Hełm Erosa", "Miecz Erosa"),
                WikiCommand.combinedSuggestions(List.of("Buty_Erosa", "Buty Erosa", "Hełm_Erosa"),
                        List.of("Buty Erosa", "Hełm Erosa", "Miecz Erosa"), "Erosa", 3));
        assertEquals(List.of("Buty Erosa"), WikiCommand.combinedSuggestions(
                List.of("Buty__Erosa"), List.of("buty\u00a0Erosa", "Buty  Erosa"), "buty", 20));
    }

    @Test void unicodeIdentityKeepsRunesAccentsAndPunctuationDistinct() {
        var titles = List.of("Pergamin ᚦ", "Pergamin_ᚱ", "Pergamin ᛚ");
        var catalog = new WikiCatalog(Map.of(), titles.stream().map(WikiEntry::wikiOnly).toList());
        assertEquals(3, catalog.size());
        assertEquals("Pergamin ᛚ", catalog.exact("Pergamin_ᛚ").orElseThrow().title());
        assertEquals(List.of("Pergamin ᛚ"), WikiSearch.find("Pergamin ᛚ", titles).titles());
        assertEquals(3, WikiCommand.combinedSuggestions(titles, titles, "pergamin", 20).size());
        assertNotEquals(WikiCatalog.titleKey("Łuk"), WikiCatalog.titleKey("Luk"));
        assertNotEquals(WikiCatalog.titleKey("Miecz (I)"), WikiCatalog.titleKey("Miecz I"));
        assertEquals(WikiCatalog.titleKey("Hełm"), WikiCatalog.titleKey("He\u0142m"));
        assertEquals(WikiCatalog.titleKey("Żeton"), WikiCatalog.titleKey("Z\u0307eton"));
    }
}
