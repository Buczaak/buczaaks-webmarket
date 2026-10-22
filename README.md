# KryptoGielda

Plugin na serwery Paper 1.21.x (Java 21): **fikcyjna giełda kryptowalut z własną stroną WWW**.
Wszystko opiera się wyłącznie na walucie serwerowej (Vault / EssentialsX). Do giełdy nie wchodzą ani z niej nie wychodzą
prawdziwe pieniądze – plugin nie ma żadnej integracji płatniczej.

Plugin zastępuje dawny zestaw *PHP + Skript + skript-db*: jedna paczka `.jar` obsługuje rynek, boty, wydarzenia,
menu w grze, komendy i stronę WWW (wbudowany serwer HTTP).

## Co potrafi

- 8 domyślnych kryptowalut (konfigurowalne), ceny liczone przez **krzywą rynkową zależną od wielkości zlecenia**
  (duże zakupy podnoszą cenę, duże sprzedaże obniżają; nie da się zarobić na dzieleniu zleceń ani na kupnie i natychmiastowej sprzedaży).
- Prowizja przy sprzedaży (domyślnie 2%).
- Boty tworzące popyt i podaż, cykl rynku co 5 minut (nastrój rynku, powrót do średniej, cichy trend admina), losowe wydarzenia z newsami.
  Boty i wydarzenia są **jawne**: oznaczone na stronie i w czacie, w stopce strony jest informacja o nich.
- Zlecenia limit (kupno gdy cena spadnie, sprzedaż gdy wzrośnie) z blokowaniem środków.
- Strona WWW: wykres świecowy, portfel z zyskiem/stratą, historia, ranking, newsy, kanał „Na żywo”, panel administratora.
- Logowanie na stronę **jednorazowym kodem z gry** (`/gielda link`) – bez haseł.
- Menu w grze (`/gielda`): portfel, rynek, broker (KUP 1/10/100/MAX, SPRZEDAJ 1/10/100/WSZYSTKO).
- Wpłata/wypłata między grą a kontem giełdowym (`/wplac`, `/wyplac`); wypłaty przez kolejkę odporną na awarie.
- Baza: SQLite (domyślnie, zero konfiguracji) lub MySQL.

## Wymagania

- Paper (lub fork) 1.21.x, Java 21
- **Vault** + plugin ekonomii (np. EssentialsX)

## Instalacja

1. Wrzuć `KryptoGielda-1.0.0.jar` do `plugins/` (razem z Vault i pluginem ekonomii).
2. Uruchom serwer – powstanie `plugins/KryptoGielda/config.yml` i `messages.yml`.
3. Dopasuj `config.yml` (waluta, kryptowaluty, port strony, `public-url`), zrestartuj serwer.
4. W grze: `/gielda link` → wpisz kod na stronie.

Zmiany w `config.yml` wymagają restartu. `/gieldaadmin reload` przeładowuje tylko `messages.yml`.

## Komendy i uprawnienia

| Komenda | Opis | Uprawnienie |
|---|---|---|
| `/gielda` (`menu`, `portfel`, `rynek`, `saldo`, `link`, `help`) | menu i informacje | `gielda.use` (domyślnie wszyscy) |
| `/wplac <kwota>` | z gry na konto giełdowe | `gielda.use` |
| `/wyplac <kwota>` | z konta giełdowego do gry | `gielda.use` |
| `/gieldaadmin cena\|pump\|dump\|trend <krypto> <wartość>` | ręczne sterowanie ceną / cichy trend | `gielda.admin` (domyślnie OP) |
| `/gieldaadmin wymus`, `event [numer]` | wymuś cykl rynku / odpal event | `gielda.admin` |
| `/gieldaadmin reset potwierdz [wipe]` | reset giełdy (z `wipe` także konta graczy) | `gielda.admin` |
| `/gieldaadmin gracz <nick> <saldo\|krypto> <dodaj\|odejmij\|ustaw> <wartość>` | korekta kont | `gielda.admin` |
| `/gieldaadmin wyplaty [zwroc\|zakoncz <id>]` | podgląd i naprawa wypłat przerwanych awarią | `gielda.admin` |
| `/gieldaadmin import [force]` | import ze starej giełdy (patrz niżej) | `gielda.admin` |

Panel admina na stronie dostają tylko osoby, które zalogowały się kodem wygenerowanym z uprawnieniem `gielda.admin`.
Wszystkie akcje admina są zapisywane w logu serwera (`[AUDYT]`).

## Strona WWW – port, HTTPS, bezpieczeństwo

- Domyślnie strona działa na porcie **8123** (`web.port`). Otwórz go w firewallu hostingu/serwera.
- **Zalecane:** postaw przed pluginem reverse proxy z HTTPS (nginx/Caddy), ustaw `web.bind: 127.0.0.1`,
  `web.secure-cookie: true`, `web.trust-forwarded-headers: true`, `web.public-url: https://gielda.twojserwer.pl`.
  Przykład Caddy: `gielda.twojserwer.pl { reverse_proxy 127.0.0.1:8123 }`
- Nigdy nie wystawiaj na świat portu bazy danych (3306). Plugin go nie potrzebuje przy SQLite.
- Wbudowane zabezpieczenia: kody jednorazowe, sesje w ciasteczku HttpOnly + SameSite=Strict, limity zapytań, sprawdzanie `Origin`,
  limit rozmiaru żądań, nagłówki CSP, brak zewnętrznych skryptów i bibliotek na stronie, cała logika cen po stronie serwera.
- Własny wygląd: skopiuj `index.html`, `style.css`, `app.js` z jar-a do `plugins/KryptoGielda/web/` i edytuj
  (zachowaj identyfikatory elementów, na których opiera się `app.js`).

## Żywy rynek i eventy

- **Mikro-ruchy**: co kilka sekund cena każdej waluty realnie drgnie o ułamek procenta (`simulation.micro-ticks` w config.yml) – wykres „pulsuje" bez odświeżania strony. To prawdziwe zmiany ceny na serwerze, te same dla wszystkich graczy.
- **Strona** odświeża się sama co sekundę; wybrana waluta, interwał i zakładka są zapamiętywane w przeglądarce.
- **Eventy**: domyślnie 16 – dla każdej waluty jedna hossa i jedna bessa (`simulation.events.list`). Jeśli masz starą listę w config.yml, usuń blok `list:` (albo cały plik), a plugin użyje nowych.
- **Historia cen** starsza niż 48 h jest automatycznie zagęszczana (świece 5 min zostają), więc baza nie puchnie.
- Za tunelem frp wszyscy gracze widzą się jako 127.0.0.1 – limity zapytań są wtedy automatycznie zwiększone; w razie potrzeby podnieś `web.rate-limit-multiplier`.

## Kopie zapasowe

SQLite: skopiuj `plugins/KryptoGielda/gielda.db` (przy wyłączonym serwerze lub razem z plikami `-wal`/`-shm`).
MySQL: standardowy `mysqldump`.

## Import ze starej giełdy (PHP + Skript)

Przenoszone są **salda graczy i portfele**. Zlecenia limit i historia nie są przenoszone (inny model cen).
Portfele dostają bazę kosztu równą aktualnej cenie (zysk/strata liczone od momentu importu).

1. W `config.yml` sekcja `import`: `enabled: true` + dane starej bazy MySQL.
2. Restart, potem `/gieldaadmin import`.
3. Gracze, którzy nigdy nie weszli na serwer, są pomijani (`import force` dodaje ich – tylko dla serwerów offline-mode).
4. Po imporcie usuń hasło z `config.yml` i **zmień hasła starej bazy** (wcześniejsze były w plikach skryptów).

Import można powtarzać – istniejące konta są pomijane.

## Jak działa cena (skrót)

Cena po zakupie `q` sztuk: `P·exp(q/Q)`, koszt: `P·Q·(exp(q/Q) − 1)`; sprzedaż to krzywa odwrotna z prowizją.
Wynik zależy tylko od całkowitej ilości, nie od sposobu jej podziału, dlatego nie ma „farmienia” małymi zleceniami.
`Q` wynika z `market.depth-value-per-percent` (lub `coins.<nazwa>.liquidity`). Wszystkie kwoty to liczby całkowite (grosze / 1/10000 sztuki) –
bez błędów zaokrągleń zmiennoprzecinkowych.

## Rozwiązywanie problemów

- *„Nie znaleziono ekonomii Vault”* – brakuje pluginu ekonomii (np. EssentialsX) albo Vault.
- *Strona się nie otwiera* – sprawdź port w firewallu i w logu serwera linię „Strona giełdy działa na porcie…”.
- *Wypłata w stanie SENDING po awarii* – `/gieldaadmin wyplaty`; **sprawdź saldo gracza** przed `zwroc` (zwrot na konto giełdowe) lub `zakoncz`.

## Budowanie ze źródeł

```
mvn package
```
Wynik: `target/KryptoGielda-1.0.0.jar`. Testy (`mvn test`) obejmują silnik rynku, symulator i API strony.

## Licencja

KryptoGielda jest wolnym oprogramowaniem na licencji **GNU GPL v3.0 lub nowszej** (pełny tekst w pliku `LICENSE`).
Biblioteki firm trzecich: patrz `THIRD_PARTY.md`.
