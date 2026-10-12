# LainaWiki — Księga Lainy

Samodzielny plugin Paper 26.2 (Java 25) z dialogową encyklopedią przedmiotów serwera Laina.pl.

- `/wiki` — otwiera Księgę, wyszukuje przedmiot i pokazuje artykuł z Wiki.
- `/wikici` — otwiera kartę przedmiotu trzymanego w ręce; działa też przez przycisk w Księdze.
- Indeks przedmiotów aktualizuje się z Wiki w tle. `items.yml` jest lokalną mapą modeli do artykułów.
- Ogólne hasło pokazuje listę wyników zamiast otwierać pierwszy przedmiot.
  Wyszukiwanie rozpoznaje kolejność słów i proponuje podobne nazwy przy drobnych
  literówkach. Można też przeszukać całą Wiki w przeglądarce.
- „Wróć” na karcie prowadzi do zapamiętanej strony listy, wyników lub ulubionych.
  Licznik i losowanie operują na unikalnych kartach, nie wariantach modeli.
- „Pełna Wiki ↗” jest zwykłym przyciskiem; Esc na stronie głównej nie otwiera linku.
- Linki z treści artykułu prowadzą do powiązanych stron Wiki.
- Galerie pokazują pojedynczą listę linków tekstowych oraz informację o pełnej
  galerii na stronie WWW; znaczniki HTML podkreślenia nie są wyświetlane dosłownie.
- Brakujące artykuły mają czerwone odnośniki i własny komunikat. Błędy połączenia
  są zgłaszane osobno. Statusy linków pobieramy razem z artykułem i cache'ujemy
  razem z nim (nie wykonujemy osobnego zapytania dla każdego odnośnika).
- Linki w tekście korzystają z natywnego zdarzenia custom-click dialogu,
  bez wykonywania komendy callback. Są wielokrotnego użytku przez 30 minut.
- Kolory RGB napisów można ustawić w `wiki.colors` w `config.yml` jako `#RRGGBB`.
  Opcje: `text`, `heading`, `accent`, `link`, `author-link`, `missing-link`,
  `button`, `button-highlight`, `button-neutral`, `button-positive`,
  `button-secondary`. Nie zmieniają tekstury przycisków.
  Przy istniejącym configu można dopisać tę sekcję; zmiany wymagają restartu pluginu.
- Linki są przypisane do dokładnych miejsc w tekście Wiki, nie do wszystkich
  wystąpień tej samej nazwy. Mają stonowany jasnoniebieski kolor i podkreślenie;
  podział na strony oraz widok „Jak zdobyć?” zachowują ich działanie.
  W sekcji „Autorzy” odnośniki zachowują klasyczne turkusowe wyróżnienie.
- Tooltip ikony zawiera tylko czytelną nazwę artykułu, bez statystyk bazowego materiału,
  lore i zaklęć. Dotyczy też podglądu z ręki: zmieniana jest tylko kopia do dialogu,
  nigdy item gracza. Opisy i właściwości czytamy z Wiki w karcie, nie zgadujemy ich.
- Przyciski nie wyświetlają dymków po najechaniu.
- Spójna paleta menu: białe nazwy przedmiotów i działów, turkusowe akcje
  pomocnicze oraz złote wyróżnienia wyszukiwania, ulubionych i głównej nawigacji.
  Na głównej stronie dodatkowe akcenty: zielony „Przedmiot w ręce”, żółta
  „Losowa karta” i białe „Ostatnio oglądane”, bez różowych przycisków.
  Na karcie przedmiotu: zielone „Jak zdobyć?”, żółte powiązania, biały powrót
  i turkusowy odnośnik do pełnego artykułu.

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
Podczas oczekiwania pojawia się okno, które można zamknąć klawiszem Esc.
Przycisk „Zamknij” blokuje ponowne otwarcie karty przez tę odpowiedź; nie cofa wcześniej
zleconego zapisu ulubionych ani wspólnego pobrania dla innych graczy. Zamknięcie
klawiszem Esc wymaga jeszcze poprawki: odpowiedź może ponownie otworzyć kartę.

Cache mieści domyślnie 256 opisów (`wiki.article-cache-capacity`). Po wygaśnięciu
opisu plugin próbuje pobrać aktualny. Jeśli Wiki nie odpowiada, pokazuje zachowany
opis z informacją, że może być nieaktualny. Cache artykułów działa w pamięci:
po restarcie trzeba pobrać opisy ponownie. Indeks stron jest zapisywany na dysku.
Po błędzie ponowne pobranie tego samego opisu czeka 30 sekund. Nieudana
synchronizacja indeksu ponawia się po 1, 2, 4, 8, 16, następnie maksymalnie
30 minutach (nie rzadziej niż ustawiony normalny interwał). Pełny poprzedni indeks
pozostaje dostępny. Odpowiedzi HTTP mają limit 2 MiB; XML odrzuca deklaracje DTD.
Parser pomija komentarze Wiki, dekoduje podstawowe encje HTML i śledzi przekierowania.

Historia gracza jest czyszczona przy wyjściu, a zapytania i klient HTTP zamykane
przy wyłączeniu pluginu. Wyszukiwanie obsługuje nazwy bez polskich znaków, w tym `ł`.

## Budowanie

Zainstaluj Java 25 i Maven 3.9+, a następnie uruchom `mvn clean package`. Wynik to `target/LainaWiki-0.2.0.jar`.
Testy nie wymagają uruchomionego Minecrafta. Opcjonalny test z prawdziwą Wiki:
`mvn -Dwiki.live-smoke=true test package` (wymaga połączenia z Internetem).

Analiza projektu i lista dalszych usprawnień: [PROJECT_REVIEW.md](PROJECT_REVIEW.md).

## Aktualizacja 2026-10-12

Opcjonalna integracja Iskierki udostępnia metodę
`describeHeldItem(Player, boolean acquisition)` na instancji pluginu. Wywołanie musi
nastąpić na głównym wątku serwera. Wynikiem jest `CompletableFuture<Map<String,String>>`:
`status` (`ok`, `empty`, `unknown`, `missing`, `unavailable`), a dla `ok` również
`title`, `summary`, `stale`. API identyfikuje item tak jak Księga, używa wspólnego
cache i zwraca wyłącznie fragmenty Wiki. Nieznany item nie otrzymuje zgadywanego opisu.

- Podpowiedzi scalają nazwy z katalogu i indeksu Wiki przed ograniczeniem wyników.
  Warianty `Buty_Erosa` i `Buty Erosa` wyświetlają się jako jedna nazwa ze spacjami.
- Tożsamość tytułów zachowuje runy, akcenty i interpunkcję; trzy pergaminy runiczne
  pozostają osobnymi kartami. Wyszukiwanie nadal obsługuje brak polskich znaków.
- Opisy pomijają znaczniki prezentacji emoji U+FE0E/U+FE0F, zachowując symbol bazowy,
  np. serce w opisie Butów Erosa. Wygląd symbolu zależy od czcionki klienta.
- Galerie są zastępowane informacją o wersji WWW i tekstowymi linkami podpisów;
  istniejące oraz brakujące strony otrzymują odpowiednie wyróżnienie.
- Rozróżniono brak artykułu od błędu pobrania, dodano konfigurowalne kolory RGB
  i poprawiono przetwarzanie odnośników zewnętrznych.

Weryfikacja wydania: 67 testów bez błędów, w tym test z rzeczywistą Wiki.
Pozostałe ograniczenia i przypadki brzegowe opisano w
[raporcie z 2026-10-11](REVIEW-2026-10-11.md).

## Instalacja

Umieść JAR w `plugins/` i uruchom serwer. **Nie instaluj równocześnie z wersją LainaReforge zawierającą `/wiki`** — oba pluginy rejestrują tę samą komendę. Najpierw usuń moduł Wiki z LainaReforge albo użyj jego wydania bez Wiki.

Endpoint Wiki i czasy odświeżania można zmienić w `plugins/LainaWiki/config.yml`. Plugin nie zawiera systemu recyklingu ani reforgingu.
