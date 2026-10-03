package pl.laina.reforge.wiki;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WikiTextTest {
    @Test
    void interactiveButtonsDoNotLookDisabledOrMissing() {
        var aqua = net.kyori.adventure.text.format.NamedTextColor.AQUA;
        var gold = net.kyori.adventure.text.format.NamedTextColor.GOLD;
        assertEquals(aqua, WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        assertEquals(aqua, WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        assertEquals(aqua, WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.BLUE));
        assertEquals(aqua, WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.DARK_BLUE));
        assertEquals(gold, WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.RED));
        assertEquals(gold, WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.DARK_RED));
        for (var color : java.util.List.of(net.kyori.adventure.text.format.NamedTextColor.YELLOW,
                net.kyori.adventure.text.format.NamedTextColor.GREEN,
                net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE)) {
            assertEquals(color, WikiDialogService.interactiveColor(color));
        }
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.GOLD,
                WikiDialogService.interactiveColor(net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    @Test
    void iconTitlesUseReadableWikiNamesWithoutTechnicalSeparators() {
        assertEquals("Ametystowa Laska", WikiIconPresentation.readableTitle("Ametystowa_Laska"));
        assertEquals("Boski Samurajski Łuk", WikiIconPresentation.readableTitle(" Boski__Samurajski_Łuk "));
        assertEquals("Czerwony Miecz Świetlny", WikiIconPresentation.readableTitle("Czerwony Miecz Świetlny"));
    }

    @Test
    void stripsWikiBehaviorSwitchesFromBothTextModes() {
        String markup = "__NOTOC__\nOpis. __NOEDITSECTION__";
        assertEquals("Opis.", WikiArticleService.toPlainText(markup));
        assertEquals("Opis.", WikiArticleService.toDialogText(markup));
    }

    @Test
    void linkLabelsIncludeTrailingInflectionWithoutChangingDestination() {
        String markup = "Poprzednio byla [[Mod]]em i [[Gracz|gracz]]ami.";
        var links = WikiArticleService.extractLinks(markup, "https://wiki.laina.pl/");
        assertEquals("Modem", links.get(0).label());
        assertEquals("Mod", links.get(0).targetTitle());
        assertEquals("graczami", links.get(1).label());
        assertEquals("Poprzednio byla Modem i graczami.", WikiArticleService.toDialogText(markup));
    }

    @Test
    void layoutSeparatesListsWithoutLosingHeadingsOrLinks() {
        String text = "Wstęp.\n\n== Zaklęcia ==\n\n• Wydajność 6\n• Szczęście 4\n"
                + "Dalszy opis.\n\n== Jak zdobyć? ==\n\n• Z Klucza Diamentowego.";
        assertEquals(java.util.List.of("Wstęp.", "== Zaklęcia ==", "• Wydajność 6\n• Szczęście 4",
                "Dalszy opis.", "== Jak zdobyć? ==", "• Z Klucza Diamentowego."),
                WikiDialogService.articleBlocks(text));
    }

    @Test
    void dialogPreservesHeadingHierarchyAndSeparatesSections() {
        String result = WikiArticleService.toDialogText("Wstęp.\r\n== Opis ==\r\nTreść.\r\n"
                + "=== Zaklęcia ===\r\n* Wydajność 6\r\n* Szczęście 4");
        assertEquals("Wstęp.\n\n== Opis ==\n\nTreść.\n\n=== Zaklęcia ===\n\n• Wydajność 6\n• Szczęście 4", result);
        assertEquals(2, WikiArticleService.headingLevel("== Opis =="));
        assertEquals(3, WikiArticleService.headingLevel("=== Zaklęcia ==="));
        assertEquals("Zaklęcia", WikiArticleService.headingLabel("=== Zaklęcia ==="));
        assertEquals(0, WikiArticleService.headingLevel("Krótki zwykły akapit"));
    }

    @Test
    void standaloneBoldLabelsBecomeHeadingsButInlineEmphasisDoesNot() {
        assertEquals("== Opis ==\n\nTekst z mocnym słowem.",
                WikiArticleService.toDialogText("'''Opis'''<br/>Tekst z '''mocnym''' słowem."));
    }

    @Test
    void paginationMovesOrphanHeadingToNextPage() {
        var pages = WikiDialogService.articlePages("Wstęp do przedmiotu.\n\n== Opis ==\n\nDalsza treść opisu przedmiotu.", 40);
        assertEquals("Wstęp do przedmiotu.", pages.getFirst());
        assertTrue(pages.get(1).startsWith("== Opis ==\n\nDalsza"));
        assertTrue(pages.stream().allMatch(page -> page.length() <= 40));
    }

    @Test
    void acquisitionLinksComeOnlyFromTheRelevantSectionIncludingSubsections() {
        String markup = "== Opis ==\nPodobny do [[Inny miecz]].\n"
                + "== Jak zdobyć? ==\nPokonaj [[Smok Endu|Smoka]].\n"
                + "=== Składniki ===\nUżyj [[Diament]].\n"
                + "== Autorzy ==\n[[Autor]].";
        var links = WikiArticleService.extractLinks(markup, "https://wiki.laina.pl/");
        assertEquals(4, links.size());
        assertFalse(links.get(0).acquisition());
        assertTrue(links.get(1).acquisition());
        assertEquals("Smok Endu", links.get(1).targetTitle());
        assertEquals("Smoka", links.get(1).label());
        assertTrue(links.get(2).acquisition());
        assertFalse(links.get(3).acquisition());
    }

    @Test
    void externalLinksStayExternalAndDescriptionsDoNotInventAcquisitionPaths() {
        var links = WikiArticleService.extractLinks("Opis [[Smok]] i [https://laina.pl sklep].", "https://wiki.laina.pl/");
        assertFalse(links.get(0).acquisition());
        assertFalse(links.get(1).acquisition());
        assertEquals(null, links.get(1).targetTitle());
    }
    @Test
    void normalizesPolishNamesAndWikiSeparators() {
        assertEquals("uwieziony excalibur", WikiCatalog.normalize("Uwięziony_Excalibur"));
        assertEquals("krysztal empirii", WikiCatalog.normalize("Kryształ_Empirii"));
        assertEquals("lucznictwo", WikiCatalog.normalize("ŁUCZNICTWO"));
    }

    @Test
    void externalLinksUseOneClosingBracketAndPreserveFollowingText() {
        String markup = "Kup [https://laina.pl/sklep?x=1&y=2 w sklepie]. Dalej.";
        assertEquals("Kup w sklepie. Dalej.", WikiArticleService.toPlainText(markup));
        var links = WikiArticleService.extractLinks(markup, "https://wiki.laina.pl/");
        assertEquals(1, links.size());
        assertEquals("w sklepie", links.getFirst().label());
        assertEquals("https://laina.pl/sklep?x=1&y=2", links.getFirst().url());
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
