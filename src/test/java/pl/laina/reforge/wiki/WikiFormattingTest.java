package pl.laina.reforge.wiki;

import java.util.*;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import static org.junit.jupiter.api.Assertions.*;

class WikiFormattingTest {
    private static WikiRichText parse(String source) { return WikiArticleService.parseDialogText(source,"https://wiki.laina.pl/"); }
    private static Component render(WikiRichText source) { return WikiDialogService.linkedText(source,link->ClickEvent.openUrl(link.url())); }
    @Test void nestedFormattingPreservesTextAndClickTargetsThroughPagination() {
        var source=parse("A <b>mocny <u>[[Gnom|długi odnośnik]]</u></b> i <i>kursywa</i>.");
        assertEquals("A mocny długi odnośnik i kursywa.",source.text());
        for (var page:WikiDialogService.articleRichPages(source,8)) {
            var component=render(page);
            assertEquals(page.text(),PlainTextComponentSerializer.plainText().serialize(component));
            for(var part:component.children()) if(part.clickEvent()!=null) {
                assertEquals(TextDecoration.State.TRUE,part.decoration(TextDecoration.BOLD));
                assertEquals(TextDecoration.State.TRUE,part.decoration(TextDecoration.UNDERLINED));
                assertTrue(((ClickEvent.Payload.Text)part.clickEvent().payload()).value().endsWith("Gnom"));
            }
        }
        var italic=render(source).children().stream().filter(part->PlainTextComponentSerializer.plainText().serialize(part).equals("kursywa")).findFirst().orElseThrow();
        assertEquals(TextDecoration.State.TRUE,italic.decoration(TextDecoration.ITALIC));
    }
    @Test void htmlAliasesWikiQuotesAndUnclosedFormattingAreHandledWithoutMarkersLeaking() {
        for(String markup:List.of("<strong>tekst</strong>","'''tekst''' obok","<b><b>tekst</b></b>","<b>tekst")) {
            var text=parse(markup);
            assertTrue(text.formats().stream().anyMatch(span->span.decoration()==TextDecoration.BOLD),markup);
            assertFalse(text.text().chars().anyMatch(c->c>=0xE000&&c<=0xE007));
        }
        assertTrue(parse("<em>tekst</em>").formats().stream().anyMatch(span->span.decoration()==TextDecoration.ITALIC));
        assertTrue(parse("<del>tekst</del>").formats().stream().anyMatch(span->span.decoration()==TextDecoration.STRIKETHROUGH));
        assertTrue(parse("''tekst'' obok").formats().stream().anyMatch(span->span.decoration()==TextDecoration.ITALIC));
    }
    @Test void allReportedBreakVariantsProduceNewlinesAndNoTags() {
        for(String tag:List.of("<br>","<br/>","<br />","</br>","&lt;br/&gt;","&lt;/br&gt;","&#60;br/&#62;"))
            assertEquals("A\nB",parse("A"+tag+"B").text(),tag);
    }
    @Test void clippingKeepsStylesOffTheTruncationNoticeAndUnformattedText() {
        var source=parse("<u>abcdefghij</u> zwykły").truncate(5," KONIEC");
        var components=render(source).children();
        assertEquals(TextDecoration.State.TRUE,components.getFirst().decoration(TextDecoration.UNDERLINED));
        assertNotEquals(TextDecoration.State.TRUE,components.getLast().decoration(TextDecoration.UNDERLINED));
        assertEquals("abcde KONIEC",PlainTextComponentSerializer.plainText().serialize(render(source)));
    }
    @Test void gallerySupportsExplicitTargetWithoutCaptionLinkAndDistinctUnicodeTitles() {
        var source=parse("<gallery>one.png|link=Żeton|Żeton\ntwo.png|link=Żeton|[[Żeton]]\nthree.png|link=Pergamin ᛚ|Pergamin\nfour.png|link=Pergamin ᚱ|Pergamin</gallery>");
        assertEquals(3,source.links().size());
        assertEquals(List.of("Żeton","Pergamin ᛚ","Pergamin ᚱ"),source.links().stream().map(span->span.link().targetTitle()).toList());
        assertFalse(source.text().contains("link="));
        assertTrue(source.text().contains(WikiArticleService.GALLERY_NOTICE));
    }
    @Test void missingLinksPreserveAccentsAndDecoration() {
        var source=parse("<b>[[Łuk]]</b> [[Luk]]");
        var component=WikiDialogService.linkedText(source,link->ClickEvent.openUrl(link.url()),Set.of(WikiCatalog.titleKey("Łuk")),WikiPalette.defaults());
        var links=component.children().stream().filter(part->part.clickEvent()!=null).toList();
        assertEquals(NamedTextColor.RED,links.getFirst().color());
        assertEquals(TextDecoration.State.TRUE,links.getFirst().decoration(TextDecoration.BOLD));
        assertEquals(WikiDialogService.LINK_COLOR,links.getLast().color());
    }
}
