# Przegląd Księgi Lainy — 2026-10-03

## Wniosek

Projekt ma sensowną podstawę: osobne pobieranie HTTP, bezpieczny zapis ulubionych,
identyfikacja przedmiotów po materiale i CustomModelData oraz opisy pochodzące z Wiki.
Największe potwierdzone problemy dotyczyły nawigacji, wyszukiwania i reakcji na awarie,
nie kolorów. Poniższe poprawki są wdrożone w kodzie; odbiór wizualny wymaga nowej
wersji pluginu na uruchomionym serwerze i sprawdzenia klientem Minecrafta.

## Zakres

Przejrzano wszystkie klasy pluginu, konfigurację, dane katalogu, testy, budowanie,
obsługę cyklu życia oraz lokalny log serwera. Zachowano wcześniejsze zmiany użytkownika,
paletę menu, styl linków w sekcji Autorzy i tooltip pokazujący wyłącznie nazwę.
To przegląd funkcjonalny i odpornościowy, nie certyfikacja bezpieczeństwa ani test obciążeniowy.

## Potwierdzone problemy i wdrożone poprawki

| Priorytet | Problem przed poprawką | Zmiana |
| --- | --- | --- |
| P1 | Link WWW był `exitAction` strony głównej, czyli również akcją Esc. | Link jest zwykłym przyciskiem, nie akcją zamknięcia. |
| P2 | Ogólne hasło otwierało pierwszy pasujący przedmiot. | Lista wyników, dokładne dopasowanie na początku, stronicowanie. |
| P2 | Powrót z karty uruchamiał `/wiki` i gubił stronę listy. | Zapamiętywanie kategorii, strony, wyników, historii i ulubionych. |
| P2 | Zamknięcie menu podczas pobierania nie anulowało oczekującego wyświetlenia. | Okno ładowania z Esc/Zamknij i unieważnieniem właściwego tokenu odpowiedzi. |
| P2 | Nieudany indeks czekał na następny normalny interwał, domyślnie 6 godzin. | Ponawianie po 1–30 minutach; po sukcesie powrót do normalnego interwału. |
| P2 | Każde kolejne otwarcie niedostępnego opisu ponownie obciążało Wiki. | 30-sekundowa przerwa per tytuł, ograniczona pamięć błędów, zachowanie starego opisu. |
| P2 | Limit długości opisu działał dopiero po nieograniczonym pobraniu odpowiedzi. | Limit 2 MiB podczas pobierania, również bez nagłówka Content-Length. |
| P2 | Licznik i losowanie liczyły warianty modeli jako osobne karty. | Unikalne tytuły w liczniku i puli losowania; warianty nadal rozpoznawane w ręce. |
| P2 | Tab dla kolejnych słów zwracał całą nazwę, dublując wpisany początek. | Uzupełnianie pozostałej części nazwy. |
| P2 | API artykułu nie żądało rozwiązania przekierowania Wiki. | Parametr `redirects=true`, użycie zwróconego tytułu docelowego. |
| P3 | Komentarze i podstawowe encje mogły trafiać do opisu/linków. | Usuwanie komentarzy i dekodowanie encji z zachowaniem zakresów linków. |
| P3 | Główny link ignorował skonfigurowany adres Wiki. | Link główny i wyszukiwarka korzystają z `wiki.public-url`. |

P1 oznacza istotne nieoczekiwane zachowanie, nie podatność bezpieczeństwa.
Źródła opisujące semantykę dialogów:
[Minecraft Java 1.21.6 — dialog exit actions](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6),
[Paper — Dialogs](https://docs.papermc.io/paper/dev/dialogs/).

## Nowe udogodnienia

- Podpowiedzi przy drobnych literówkach, bez automatycznego otwierania przypadkowej karty.
- Wyszukiwanie słów w innej kolejności, np. „łuk boski”.
- Przycisk wyszukiwania w całej Wiki, także poza kategorią Przedmioty.
- Powrót do właściwego miejsca na liście nawet po przejściu przez powiązane artykuły.

## Dane: co naprawdę zawiera katalog

Test danych wykazał **1757 modeli**, z czego **858 ma tytuł/nazwę**,
co daje **689 unikalnych kart**; **899 modeli nie ma przypisanego tytułu**.
Nie znaleziono powtórzonych par materiał + CustomModelData.
Brakujące przypisania są ograniczeniem danych, nie powodem do wymyślania opisów.
Ostatni log lokalnego serwera przed aktualizacją potwierdzał indeks **850 stron Wiki**.
Indeks pobierany w tle i statyczna mapa modeli to dwa różne źródła:
nowy artykuł może być wyszukiwany bez posiadania przypisanego modelu/ikony.

## Co pozostaje do dalszego rozwoju

1. **Parser bardziej złożonych stron — największy sens.** Obecny parser jest
   celowo ograniczony: tabele, zagnieżdżone szablony i inne konstrukcje MediaWiki
   nie są wiernie odwzorowywane. Rozwój zacząć od zestawu rzeczywistych przykładów
   i testów, nie od kolejnych ogólnych wyrażeń regularnych.
2. **Spis treści dłuższych kart.** Przyciski do sekcji Zaklęcia, Jak zdobyć?, Autorzy,
   prowadzące do odpowiedniej strony opisu. Treść nadal wyłącznie z Wiki.
3. **Diagnostyka administracyjna.** Status ostatniej synchronizacji, liczba błędów,
   ręczne odświeżenie z uprawnieniem oraz lista modeli bez mapowania.
4. **Opcjonalny cache opisów na dysku.** Dostęp do ostatnich opisów po restarcie przy
   awarii Wiki; konieczny limit rozmiaru i czytelna data aktualności.
5. **Jedno miejsce definiujące kolory/różne role przycisków.** Obecna paleta jest
   zachowana; późniejsze zmiany powinny wynikać z roli, nie poprawiania każdego menu osobno.

Nie dodawać automatycznie statystyk z bazowego itemu ani domniemanych dropów.
Nie wyłączać globalnie ostrzeżenia klienta przed otwieraniem WWW.

## Weryfikacja

- Maven / Java 25: **57 testów, 0 błędów, 0 niepowodzeń, 0 pominiętych** z włączonym
  `wiki.live-smoke=true`; test live pobiera Pisankę i Lapisowy Kilof z prawdziwej Wiki.
- Nowe testy: ranking, literówki, polskie znaki, kolejność słów, Tab, licznik,
  interwały ponawiania, adresy WWW, encje/komentarze, przerwa po błędach, limit HTTP,
  żądanie przekierowań i kontrola danych katalogu.
- Istniejące testy nadal kontrolują zapis ulubionych, limity kolejki/cache,
  współdzielenie żądań, linki i dzielenie opisu na strony.
- `git diff --check`: bez błędów białych znaków.
- JAR przygotowany jako `LainaWiki-0.2.0.jar`; wersja nazwy pozostaje zgodna z instalacją.
- Paczka znajduje się w `plugins/update/` lokalnego serwera i zostanie zastosowana
  przy kolejnym uruchomieniu. SHA-256 pliku wynikowego i odłożonej kopii jest zgodny:
  `2A4278C36CDF7492F4DEDAFD82EF476B51A7D88281C8DDCECDAA1DC017D6668D`.
  Poprzedni JAR zachowano w `backups/LainaWiki-before-review-2026-10-03/`.
  Działającego serwera nie restartowano; nie jest to potwierdzenie działania nowego JAR w Paper.
- Zmian nie wysłano do GitHub w ramach tego przeglądu.

### Do sprawdzenia klientem po restarcie

1. `/wiki miecz` → lista, druga strona → karta → Wróć, nadal druga strona.
2. `/wiki lapisowy kilofg` → podpowiedź zamiast błędnej karty.
3. Esc na głównej stronie nie otwiera przeglądarki.
4. Esc podczas pobierania zamyka okno i odpowiedź go później nie otwiera.
5. Ulubione, linki wewnętrzne, „Poprzednia karta”, „Jak zdobyć?” oraz tytuł tooltipa.
6. Czytelność głównego menu przy używanej skali GUI i najdłuższych nazwach.

Nie wykonano testu multiplayer ani obciążeniowego; testy automatyczne nie zastępują
oceny wyglądu i obsługi natywnych dialogów przez klienta.

## Osobna uwaga o lokalnym serwerze

W logu sprzed aktualizacji jest `Error loading saved data minecraft:raids` z
`Unexpected end of ZLIB input stream`: problem odczytu zapisanych danych rajdów świata.
To nie błąd Księgi. Nie zmieniano ani nie usuwano danych świata; ewentualna naprawa
wymaga osobnej kopii zapasowej i diagnozy konkretnego pliku.
