package pl.laina.reforge.wiki;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiRichTextTest {
    private static final String BASE = "https://wiki.laina.pl/";

    @Test
    void pisankaRepetitionDoesNotTurnUnlinkedIntroIntoAnotherLink() {
        var text = WikiArticleService.parseDialogText("Tylko podczas eventu wielkanocnego.\n"
                + "== Jak zdobyć? ==\nTylko podczas [[Wiosenne Jajobranie|eventu wielkanocnego]].", BASE);
        assertEquals(1, text.links().size());
        assertEquals(text.text().lastIndexOf("eventu wielkanocnego"), text.links().getFirst().start());
        Component rendered = render(text);
        assertEquals(text.text(), plain(rendered));
        assertEquals(1, clickable(rendered).size());
        assertEquals("eventu wielkanocnego", plain(clickable(rendered).getFirst()));
        assertEquals(NamedTextColor.WHITE, rendered.children().getFirst().color());
        assertNull(rendered.children().getFirst().clickEvent());
    }

    @Test
    void identicalLabelsKeepDifferentDestinationsAndDoNotLinkPlainSubstrings() {
        var text = WikiArticleService.parseDialogText("Miecz. [[Pierwszy|Miecz]], [[Drugi|Miecz]] i Mieczyk.", BASE);
        var rendered = clickable(render(text));
        assertEquals(2, rendered.size());
        assertEquals(WikiUrls.article(BASE, "Pierwszy"), ((ClickEvent.Payload.Text) rendered.get(0).clickEvent().payload()).value());
        assertEquals(WikiUrls.article(BASE, "Drugi"), ((ClickEvent.Payload.Text) rendered.get(1).clickEvent().payload()).value());
    }

    @Test
    void fullInflectedLabelIsLinkedWithoutColoringPlainCopies() {
        var text = WikiArticleService.parseDialogText("Modem i [[Mod]]em, potem Modem.", BASE);
        assertEquals("Modem i Modem, potem Modem.", text.text());
        assertEquals(1, text.links().size());
        assertEquals(8, text.links().getFirst().start());
        assertEquals("Modem", plain(clickable(render(text)).getFirst()));
    }

    @Test
    void externalAndInternalLinksStayInSourceOrder() {
        var text = WikiArticleService.parseDialogText("Sklep: [https://laina.pl sklep], potem [[Smok Endu]].", BASE);
        assertEquals("Sklep: sklep, potem Smok Endu.", text.text());
        assertNull(text.links().getFirst().link().targetTitle());
        assertEquals("Smok Endu", text.links().get(1).link().targetTitle());
    }

    @Test
    void cleanupRetainsHeadingFormattingButDropsRemovedLinksAndAllMarkers() {
        String source = "__NOTOC__\n[[Plik:item.png|prawo]]\n'''Autorzy'''\n"
                + "* [[Autor]].\n{{ukryty|[[Ukryty]]}}\n[[Kategoria:Przedmioty]]";
        var text = WikiArticleService.parseDialogText(source, BASE);
        assertEquals(WikiArticleService.toDialogText(source), text.text());
        assertEquals(1, text.links().size());
        assertEquals("Autor", text.text().substring(text.links().getFirst().start(), text.links().getFirst().end()));
        assertTrue(text.text().chars().noneMatch(c -> c >= 0xE000 && c <= 0xE003));
    }

    @Test
    void pagesSplitInsideLongLinksWithoutDroppingClickActions() {
        var text = WikiArticleService.parseDialogText("[[Cel|BardzoDługiJednowyrazowyOdnośnik]]", BASE);
        var pages = WikiDialogService.articleRichPages(text, 8);
        assertTrue(pages.size() > 1);
        assertEquals(text.text(), pages.stream().map(WikiRichText::text).collect(java.util.stream.Collectors.joining()));
        for (var page : pages) {
            assertEquals(page.text(), plain(render(page)));
            assertEquals(1, clickable(render(page)).size());
            assertEquals("Cel", page.links().getFirst().link().targetTitle());
        }
    }

    @Test
    void repeatedBlocksDoNotBorrowLinksFromEachOther() {
        var text = WikiArticleService.parseDialogText("Smok.\n\n[[Smok]].\n\nSmok.", BASE);
        var blocks = WikiDialogService.richParts(text, WikiDialogService.articleBlocks(text.text()));
        assertEquals(3, blocks.size());
        assertTrue(blocks.get(0).links().isEmpty());
        assertEquals(1, blocks.get(1).links().size());
        assertTrue(blocks.get(2).links().isEmpty());
    }

    @Test
    void articleLimitClipsLinkRangesWithoutLinkingTheTruncationNotice() {
        var text = WikiArticleService.parseDialogText("[[Cel|abcdefghij]]", BASE).truncate(5, "\nDalszy opis.");
        assertEquals("abcde\nDalszy opis.", text.text());
        assertEquals("abcde", plain(clickable(render(text)).getFirst()));
        assertEquals(NamedTextColor.WHITE, render(text).children().getLast().color());
    }

    @Test
    void linksUseSubtleColorAndKeepUnderlineAndClickAction() {
        var text = WikiArticleService.parseDialogText("[[Smok]]", BASE);
        var link = clickable(render(text)).getFirst();
        assertEquals(WikiDialogService.LINK_COLOR, link.color());
        assertNotEquals(NamedTextColor.AQUA, link.color());
        assertEquals(TextDecoration.State.TRUE, link.decoration(TextDecoration.UNDERLINED));
    }

    private static Component render(WikiRichText text) {
        return WikiDialogService.linkedText(text, link -> ClickEvent.openUrl(link.url()));
    }

    @Test
    void onlyAuthorSectionAndItsSubsectionsUseNormalAquaLinks() {
        var text = WikiArticleService.parseDialogText("Opis [[Autor]].\n== Autorzy ==\n[[Autor]].\n"
                + "=== Grafika ===\n[[Inny]].\n== Jak zdobyć? ==\n[[Smok]].", BASE);
        var links = clickable(render(text));
        assertEquals(4, links.size());
        assertEquals(WikiDialogService.LINK_COLOR, links.get(0).color());
        assertEquals(NamedTextColor.AQUA, links.get(1).color());
        assertEquals(NamedTextColor.AQUA, links.get(2).color());
        assertEquals(WikiDialogService.LINK_COLOR, links.get(3).color());
    }

    @Test
    void boldAuthorHeadingKeepsNormalHighlightAfterPaginationAndBlockSlicing() {
        var text = WikiArticleService.parseDialogText("<big>'''Autorzy'''</big>\n"
                + "* Autorem jest [[Autor|BardzoDługiJednowyrazowyNickAutora]].", BASE);
        assertTrue(text.links().getFirst().author());
        int coloredFragments = 0;
        for (var page : WikiDialogService.articleRichPages(text, 14)) {
            for (var block : WikiDialogService.richParts(page, WikiDialogService.articleBlocks(page.text()))) {
                for (var link : clickable(render(block))) {
                    assertEquals(NamedTextColor.AQUA, link.color());
                    assertEquals(TextDecoration.State.TRUE, link.decoration(TextDecoration.UNDERLINED));
                    coloredFragments++;
                }
            }
        }
        assertTrue(coloredFragments > 1);
    }

    private static List<Component> clickable(Component component) {
        return component.children().stream().filter(child -> child.clickEvent() != null).toList();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
