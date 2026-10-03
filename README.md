# LainaWiki — Księga Lainy

Samodzielny plugin Paper 26.2 (Java 25) z dialogową encyklopedią przedmiotów serwera Laina.pl.

- `/wiki` — otwiera Księgę, wyszukuje przedmiot i pokazuje artykuł z Wiki.
- `/wikici` — otwiera kartę przedmiotu trzymanego w ręce; działa też przez przycisk w Księdze.
- Indeks przedmiotów aktualizuje się z Wiki w tle. `items.yml` jest lokalną mapą modeli do artykułów.
- Linki z treści artykułu prowadzą do powiązanych stron Wiki.
- Tooltip ikony zawiera tylko czytelną nazwę artykułu, bez statystyk bazowego materiału,
  lore i zaklęć. Dotyczy też podglądu z ręki: zmieniana jest tylko kopia do dialogu,
  nigdy item gracza. Opisy i właściwości czytamy z Wiki w karcie, nie zgadujemy ich.
- Przyciski nie wyświetlają dymków po najechaniu.

## Ulubione i ścieżki zdobywania

Na stronie głównej wybierz **★ Ulubione**, a na karcie **★ Dodaj do ulubionych**
lub **★ Usuń z ulubionych**. Lista mieści 100 kart na gracza, jest osobna dla każdego
UUID i pozostaje po restarcie. Pliki w `plugins/LainaWiki/favorites/` są zapisywane
w tle przez jednego wykonawcę z podmianą pliku. Nie edytuj ich podczas pracy serwera.

**Jak zdobyć?** pokazuje treść odpowiedniej sekcji artykułu (np. „Jak zdobyć?”,
„Pozyskiwanie” lub „Crafting”), jeśli taka istnieje. Nie dopowiada dropów ani receptur.
Kliknięcie wewnętrznego linku otwiera kolejny artykuł w Księdze; **← Poprzednia karta**
pozwala wrócić. **Powiązane artykuły** zbiera linki z aktualnego opisu, a w widoku
zdobywania ogranicza listę do tej sekcji. Linki zewnętrzne nadal otwierają przeglądarkę.

## Odporność i obsługa zapytań

Artykuły pobierane są na trzech osobnych wątkach, z kolejką do 32 zadań. Równoczesne
żądania tego samego artykułu współdzielą pobranie. Wyświetlanie dialogów odbywa się
na głównym wątku Paper; starsza odpowiedź nie zastępuje nowej karty ani menu.

Cache mieści domyślnie 256 opisów (`wiki.article-cache-capacity`). Po wygaśnięciu
opisu plugin próbuje pobrać aktualny. Jeśli Wiki nie odpowiada, pokazuje zachowany
opis z informacją, że może być nieaktualny. Cache artykułów działa w pamięci:
po restarcie trzeba pobrać opisy ponownie. Indeks stron jest zapisywany na dysku.

Historia gracza jest czyszczona przy wyjściu, a zapytania i klient HTTP zamykane
przy wyłączeniu pluginu. Wyszukiwanie obsługuje nazwy bez polskich znaków, w tym `ł`.

## Budowanie

Zainstaluj Java 25 i Maven 3.9+, a następnie uruchom `mvn clean package`. Wynik to `target/LainaWiki-0.2.0.jar`.

## Instalacja

Umieść JAR w `plugins/` i uruchom serwer. **Nie instaluj równocześnie z wersją LainaReforge zawierającą `/wiki`** — oba pluginy rejestrują tę samą komendę. Najpierw usuń moduł Wiki z LainaReforge albo użyj jego wydania bez Wiki.

Endpoint Wiki i czasy odświeżania można zmienić w `plugins/LainaWiki/config.yml`. Plugin nie zawiera systemu recyklingu ani reforgingu.
