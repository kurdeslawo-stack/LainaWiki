package pl.laina.reforge.wiki;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WikiTextTest {
    @Test
    void normalizesPolishNamesAndWikiSeparators() {
        assertEquals("uwieziony excalibur", WikiCatalog.normalize("Uwięziony_Excalibur"));
    }

    @Test
    void convertsLainaWikiMarkupToReadableDialogText() {
        String input = "[[Plik:lapis_sword.png|prawo]]\n'''Opis'''<br/>Druga linia\n"
                + "== Jak zdobyć? ==\n* Z [[Klucz Diamentowy|Klucza Diamentowego]].\n"
                + "[[Kategoria:Przedmioty]]";
        String result = WikiArticleService.toPlainText(input);

        assertTrue(result.contains("Opis\nDruga linia"), result);
        assertTrue(result.contains("Jak zdobyć?\n• Z Klucza Diamentowego."), result);
        assertFalse(result.contains("Plik:"));
        assertFalse(result.contains("Kategoria:"));
    }

    @Test
    void articlePagesDoNotCutWordsOrLoseText() {
        String text = "Pierwszy akapit.\nDrugi akapit o przedmiocie.\nTrzeci akapit.";
        var pages = WikiDialogService.articlePages(text, 29);
        assertEquals(text, String.join("\n", pages));
        assertTrue(pages.stream().allMatch(page -> page.length() <= 29));
    }

    @Test
    void bowBelongsToWeaponsAndUnknownTypesToOther() {
        assertEquals(WikiCategory.WEAPONS, WikiCategory.fromType("bow"));
        assertEquals(WikiCategory.OTHER, WikiCategory.fromType("unlisted"));
    }

    @Test
    void createsRealMediaWikiArticleUrls() {
        assertEquals("https://wiki.laina.pl/index.php?title=Lapisowy_Kilof",
                WikiUrls.article("https://wiki.laina.pl/", "Lapisowy Kilof"));
        assertEquals("https://wiki.laina.pl/index.php?title=Smok_Endu",
                WikiUrls.article("https://wiki.laina.pl/index.php?title={title}", "Smok Endu"));
    }

    @Test
    void extractsClickableWikiLinksWithoutFilesOrCategories() {
        var links = WikiArticleService.extractLinks(
                "Z [[Smok Endu|Smoka Endu]] i [[Klucz Diamentowy]]. "
                        + "[[Plik:kilof.png]] [[Kategoria:Przedmioty]]",
                "https://wiki.laina.pl/");
        assertEquals(2, links.size());
        assertEquals("Smoka Endu", links.getFirst().label());
        assertEquals("https://wiki.laina.pl/index.php?title=Smok_Endu", links.getFirst().url());
    }
}
