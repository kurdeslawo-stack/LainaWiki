# LainaWiki — Księga Lainy

Samodzielny plugin Paper 26.2 (Java 25) z dialogową encyklopedią przedmiotów serwera Laina.pl.

- `/wiki` — otwiera Księgę, wyszukuje przedmiot i pokazuje artykuł z Wiki.
- `/wikici` — otwiera kartę przedmiotu trzymanego w ręce; działa też przez przycisk w Księdze.
- Indeks przedmiotów aktualizuje się z Wiki w tle. `items.yml` jest lokalną mapą modeli do artykułów.
- Linki z treści artykułu prowadzą do powiązanych stron Wiki.

## Budowanie

Zainstaluj Java 25 i Maven 3.9+, a następnie uruchom `mvn clean package`. Wynik to `target/LainaWiki-0.1.0.jar`.

## Instalacja

Umieść JAR w `plugins/` i uruchom serwer. **Nie instaluj równocześnie z wersją LainaReforge zawierającą `/wiki`** — oba pluginy rejestrują tę samą komendę. Najpierw usuń moduł Wiki z LainaReforge albo użyj jego wydania bez Wiki.

Endpoint Wiki i czasy odświeżania można zmienić w `plugins/LainaWiki/config.yml`. Plugin nie zawiera systemu recyklingu ani reforgingu.
