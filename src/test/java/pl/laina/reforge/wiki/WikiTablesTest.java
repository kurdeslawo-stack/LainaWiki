package pl.laina.reforge.wiki;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiTablesTest {
    @Test void dailyRewardsKeepEveryAmountAndClickableItem() {
        String source = """
                {| class="wikitable"
                |-
                ! Dzień
                ! Nagroda
                ! Ilość, jeśli nie należysz do gildii
                ! Ilość, jeśli należysz do gildii
                ! Ilość, jeśli gildia posiada przynajmniej 5 poziom
                ! Ilość, jeśli gildia posiada 10 poziom
                |-
                | 6 || [[Księga Morałów|Księga]] || 8 || 16 || 24 || 32
                |}
                """;
        var parsed = WikiArticleService.parseDialogText(source, "https://wiki.laina.pl/");
        assertTrue(parsed.text().contains("Dzień 6 — Księga"));
        assertTrue(parsed.text().contains("Bez gildii: 8 · Gildia: 16"));
        assertTrue(parsed.text().contains("Gildia od poziomu 5: 24 · Gildia poziom 10: 32"));
        assertFalse(parsed.text().contains("wikitable"));
        assertEquals(1, parsed.links().size());
        assertEquals("Księga Morałów", parsed.links().getFirst().link().targetTitle());
        assertEquals("Księga", parsed.text().substring(parsed.links().getFirst().start(), parsed.links().getFirst().end()));
    }

    @Test void generalTablesKeepHeadersCaptionsAttributesAndMultilineCells() {
        String source = """
                Wstęp
                {| class="wikitable"
                |+ Zestawienie
                ! Nazwa !! Opis
                |-
                | style="color:red" | [[Cel|Etykieta]] || Pierwsza
                druga linia
                |-
                | Inny || <b>Mocny</b>
                |}
                Koniec
                """;
        var parsed = WikiArticleService.parseDialogText(source, "https://wiki.laina.pl/");
        assertTrue(parsed.text().contains("Nazwa: Etykieta\nOpis: Pierwsza druga linia"));
        assertTrue(parsed.text().contains("Zestawienie"));
        assertTrue(parsed.text().endsWith("Koniec"));
        assertEquals(1, parsed.links().size());
        assertFalse(parsed.formats().isEmpty());
        assertFalse(parsed.text().contains("color:red"));
    }
}
