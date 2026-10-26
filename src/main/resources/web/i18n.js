/* Tłumaczenia strony (PL / EN). Przełącznik w prawym górnym rogu; wybór pamiętany w przeglądarce,
 * domyślny język pochodzi z opcji `language` w config.yml (pole `lang` w /api/market). */
(function () {
  'use strict';

  const D = {
    pl: {
      'tab.market': 'Giełda', 'tab.ranking': '🏆 Ranking', 'tab.admin': '⚙️ Admin',
      'lang.switch': 'EN', 'lang.title': 'Switch to English',
      'cmd.link': '/gielda link', 'cmd.deposit': '/wplac <kwota>',
      'login.title': 'Zaloguj się', 'login.hint1': 'Wejdź na serwer i wpisz komendę', 'login.hint2': 'Otrzymasz kod ważny kilka minut.',
      'login.code-label': 'Kod z gry', 'login.code-ph': 'KOD Z GRY', 'login.submit': 'Zaloguj się',
      'login.note': 'Do czasu zalogowania możesz oglądać rynek, wykresy i ranking.',
      'wallet.title': 'Twój kapitał', 'wallet.worth': 'Majątek łącznie:', 'withdraw.label': 'Wypłata do gry',
      'withdraw.ph': 'Kwota', 'withdraw.submit': 'Wypłać do gry', 'withdraw.hint1': 'Wpłata na konto giełdowe: komenda', 'withdraw.hint2': 'w grze.',
      'portfolio.title': 'Twoje portfolio', 'orders.title': '⏲ Twoje zlecenia limit', 'news.title': '📰 Wiadomości z rynku',
      'coins.aria': 'Kryptowaluty',
      'buy.title': 'Kup (rynkowo)', 'sell.title': 'Sprzedaj (rynkowo)', 'max': 'Max:', 'have': 'Masz:', 'pcs': 'szt.',
      'qty.ph': 'Ilość', 'qty.buy-aria': 'Ilość do kupienia', 'qty.sell-aria': 'Ilość do sprzedania', 'qty.aria': 'Ilość',
      'buy.btn': 'KUP', 'sell.btn': 'SPRZEDAJ',
      'limit.title': '⏲ Zlecenia automatyczne (limit)', 'limit.qty-ph': 'Ilość (szt.)', 'limit.buy-ph': 'Gdy spadnie do', 'limit.sell-ph': 'Gdy wzrośnie do',
      'limit.price-aria': 'Cena limit', 'limit.buy-btn': 'Kupno limit', 'limit.sell-btn': 'Sprzedaż limit',
      'lines.label': '📏 Linie:', 'lines.price-ph': 'Cena', 'lines.price-aria': 'Cena linii', 'lines.support': '+ Wsparcie', 'lines.resist': '+ Opór', 'lines.clear': '🗑 Wyczyść',
      'tf.aria': 'Interwał świec', 'chart.hint': 'Kliknij na wykresie, by pobrać cenę. Kółko myszy – przybliżanie, przeciąganie – przesuwanie, dwuklik – reset widoku.',
      'chart.aria': 'Wykres świecowy', 'chart.empty': 'Brak danych wykresu',
      'history.title': 'Twoja historia', 'history.type': 'Typ', 'history.qty': 'Ilość', 'history.rate': 'Kurs za szt.', 'history.amount': 'Kwota', 'history.time': 'Czas',
      'live.title': 'Na żywo',
      'ranking.title': '🏆 Ranking najbogatszych inwestorów', 'ranking.place': 'Miejsce', 'ranking.player': 'Gracz', 'ranking.worth': 'Kapitał całkowity',
      'footer': 'Waluty, ceny i portfele istnieją wyłącznie w grze. Rynek zawiera automatyczne boty i wydarzenia losowe. To nie jest prawdziwa giełda ani usługa finansowa.',
      // dynamiczne
      'fee.note': 'Prowizja przy sprzedaży: {fee}%. Duże zlecenia przesuwają cenę – podgląd kosztu pokazuje dokładną kwotę.',
      'stats': '24h: min {min} · max {max} · cena sprzedaży {sell}', 'h24': '(24h)',
      'pl.label': 'Twój zysk/strata: {diff} {cur} ({pct})',
      'news.empty': 'Brak wiadomości.', 'feed.empty': 'Jeszcze nic się nie wydarzyło.',
      'feed.bought': 'kupił', 'feed.sold': 'sprzedał', 'bot': 'bot',
      'wallet.balance-opt': 'Saldo ({cur})',
      'user.player': 'Gracz: ', 'user.logout': 'Wyloguj',
      'portfolio.empty': 'Nie masz jeszcze żadnych kryptowalut.', 'portfolio.value': 'wartość',
      'orders.empty': 'Brak aktywnych zleceń.', 'order.buy': 'KUP', 'order.sell': 'SPRZEDAJ', 'order.when-le': 'gdy cena ≤', 'order.when-ge': 'gdy cena ≥', 'order.cancel': 'Anuluj',
      'history.none': 'Brak transakcji.', 'history.buy': 'KUPNO', 'history.sell': 'SPRZEDAŻ',
      'quote.buy': 'Koszt: {value} {cur} (śr. {avg}, cena po zakupie {after})',
      'quote.sell': 'Dostaniesz: {net} {cur} (prowizja {fee}, cena po sprzedaży {after})',
      'line.support': 'Wsparcie', 'line.resist': 'Opór', 'line.remove': 'Usuń linię',
      'line.bad': 'Podaj cenę linii, np. 120.50', 'line.zero': 'Cena musi być większa od zera.',
      'toast.login-first': 'Zaloguj się, aby handlować.', 'toast.logged': 'Zalogowano jako {name}',
      'toast.withdraw': 'Wypłata zlecona – środki trafią do Twojego konta w grze za chwilę.',
      'toast.bought': 'Kupiono {qty} {coin} za {value} {cur}', 'toast.sold': 'Sprzedano {qty} {coin} za {value} {cur}',
      'toast.order-saved': 'Zlecenie limit zostało zapisane.', 'toast.order-cancelled': 'Zlecenie anulowane.',
      'err.network': 'Brak połączenia z serwerem.', 'err.http': 'Błąd {status}', 'err.unknown': 'Nieoczekiwany błąd.',
      // kody błędów z serwera (angielska wersja korzysta z tych samych kluczy)
      // admin
      'admin.title': '⚙️ Centrum dowodzenia gospodarką',
      'admin.price.title': '1. Zmień cenę', 'admin.coin-aria': 'Waluta', 'admin.mode-aria': 'Tryb',
      'admin.pump': 'PUMP (+ %)', 'admin.dump': 'DUMP (− %)', 'admin.set': 'USTAW CENĘ', 'admin.value-ph': 'np. 20', 'admin.do': 'Wykonaj 🚀',
      'admin.trend.title': '2. Cichy trend', 'admin.trend.hint': 'Ukryty bonus, który wejdzie w życie przy najbliższej aktualizacji rynku.',
      'admin.trend.ph': 'np. 40 albo -30', 'admin.trend.btn': 'Załaduj trend 🤫',
      'admin.force.title': '3. Wymuś aktualizację', 'admin.force.hint': 'Uruchamia od razu standardowy cykl cen.', 'admin.force.btn': 'Odśwież rynek 🔄',
      'admin.reset.title': '4. Reset giełdy', 'admin.reset.hint': 'Ceny wracają do startowych, historia i newsy są czyszczone. Zlecenia limit są zwracane.',
      'admin.reset.wipe': 'Dodatkowo skasuj portfele i zeruj salda graczy', 'admin.reset.ph': 'Wpisz RESET, aby potwierdzić', 'admin.reset.btn': 'RESETUJ 💀',
      'admin.player.title': '5. Konta graczy', 'admin.player.nick': 'Nick gracza', 'admin.asset-aria': 'Aktywo', 'admin.op-aria': 'Operacja',
      'admin.op.add': 'Dodaj (+)', 'admin.op.sub': 'Odejmij (−)', 'admin.op.set': 'Ustaw (=)', 'admin.player.qty-ph': 'Ilość', 'admin.player.btn': 'Wykonaj',
      'admin.events.title': '6. Eventy rynkowe',
      'admin.events.hint': 'Wszystkie eventy (z config.yml i własne). „Uruchom” odpala wydarzenie od razu; losowo pojawiają się te z wagą > 0.',
      'admin.events.import': 'Importuj domyślne eventy', 'admin.events.import-hint': 'Dodaje brakujące domyślne eventy (hossa i bessa dla każdej waluty) do własnych.',
      'admin.events.col-type': 'Rodzaj', 'admin.events.col-coin': 'Waluta', 'admin.events.col-change': 'Zmiana', 'admin.events.col-weight': 'Waga', 'admin.events.col-text': 'Treść',
      'admin.events.run': 'Uruchom', 'admin.events.delete': 'Usuń', 'admin.events.none': 'Brak eventów.', 'admin.events.src-config': 'config.yml', 'admin.events.src-custom': 'własny',
      'admin.events.new': 'Nowy event',
      'admin.events.kind-bull': 'HOSSA (wzrost)', 'admin.events.kind-bear': 'BESSA (spadek)', 'admin.events.kind-info': 'INFO (sama wiadomość)',
      'admin.events.kind-aria': 'Rodzaj eventu', 'admin.events.nocoin': '— bez waluty (sama wiadomość) —',
      'admin.events.change-ph': 'Zmiana ceny w %, np. 30 albo -25', 'admin.events.weight-ph': 'Waga losowania (0 = tylko ręcznie)',
      'admin.events.text-ph': 'Treść wiadomości dla graczy (maks. 300 znaków)', 'admin.events.add': 'Dodaj event',
      'admin.events.added': 'Event dodany.', 'admin.events.deleted': 'Event usunięty.', 'admin.events.imported': 'Dodano domyślnych eventów: {n}.', 'admin.events.ran': 'Event uruchomiony.',
      'admin.events.confirm-delete': 'Usunąć ten event?',
      'admin.coins.title': '7. Kryptowaluty',
      'admin.coins.hint': 'Własne kryptowaluty działają od razu, bez restartu. Walut z config.yml nie można edytować tutaj.',
      'admin.coins.col-coin': 'Waluta', 'admin.coins.col-start': 'Cena startowa', 'admin.coins.col-vol': 'Zmienność', 'admin.coins.col-liq': 'Płynność', 'admin.coins.col-src': 'Źródło',
      'admin.coins.edit': 'Edytuj', 'admin.coins.delete': 'Usuń', 'admin.coins.new': 'Nowa kryptowaluta', 'admin.coins.editing': 'Edycja: {id}',
      'admin.coins.id-ph': 'Nazwa (2-24 znaki: litery, cyfry, _)', 'admin.coins.price-ph': 'Cena startowa, np. 120.50',
      'admin.coins.vmin-ph': 'Zmienność min (np. 0.96)', 'admin.coins.vmax-ph': 'Zmienność max (np. 1.04)',
      'admin.coins.liq-ph': 'Płynność (opcjonalnie, 0 = auto)', 'admin.coins.icon-ph': 'Ikona (np. DIAMOND)',
      'admin.coins.add': 'Dodaj kryptowalutę', 'admin.coins.save': 'Zapisz zmiany', 'admin.coins.cancel': 'Anuluj edycję',
      'admin.coins.help': 'Zmienność to zakres mnożnika ceny w każdym cyklu rynku (0.96–1.04 = ±4%). Płynność: ile sztuk przesuwa cenę o ok. 1% (0 = wyliczana automatycznie). Cena startowa dotyczy też resetu giełdy; przy edycji aktualna cena nie zmienia się.',
      'admin.coins.added': 'Kryptowaluta dodana.', 'admin.coins.saved': 'Zapisano.', 'admin.coins.deleted': 'Kryptowaluta usunięta.',
      'admin.coins.confirm-delete': 'Usunąć kryptowalutę {id}? Tej operacji nie można cofnąć.',
      'admin.coins.src-config': 'config.yml', 'admin.coins.src-custom': 'własna',
      'admin.done.price': 'Cena zmieniona.', 'admin.done.trend': 'Cichy trend załadowany.', 'admin.done.reset': 'Giełda została zresetowana.',
      'admin.done.player': 'Zapisano.', 'admin.done.force': 'Rynek zaktualizowany.', 'admin.reset.type': 'Wpisz dokładnie RESET.',
      // błędy
      'e.NO_COIN': 'Nie ma takiej kryptowaluty.', 'e.UNAUTHORIZED': 'Zaloguj się kodem z gry.', 'e.BAD_CODE': 'Błędny lub wygasły kod. Wpisz w grze {cmd} i spróbuj ponownie.',
      'e.FORBIDDEN': 'Brak uprawnień administratora.', 'e.RATE_LIMIT': 'Zbyt wiele zapytań – chwilę poczekaj.',
      'e.INVALID_AMOUNT': 'Niepoprawna ilość lub kwota.', 'e.INVALID_PRICE': 'Niepoprawna cena.', 'e.INSUFFICIENT_FUNDS': 'Brak środków na koncie giełdowym.',
      'e.INSUFFICIENT_HOLDINGS': 'Nie masz tyle w portfelu.', 'e.PRICE_MOVED': 'Cena zmieniła się w międzyczasie. Spróbuj ponownie.',
      'e.TOO_LARGE': 'Zlecenie zbyt duże.', 'e.TOO_SMALL': 'Zlecenie jest zbyt małe.', 'e.TOO_MANY_ORDERS': 'Masz za dużo aktywnych zleceń.',
      'e.NO_ORDER': 'Nie ma takiego zlecenia.', 'e.NO_ACCOUNT': 'Nie ma takiego gracza w giełdzie (musi raz wejść na giełdę).', 'e.CONFIRM': 'Wpisz RESET, aby potwierdzić.',
      'e.BAD_NAME': 'Nazwa: 2-24 znaki, tylko litery, cyfry i _.', 'e.EXISTS': 'Kryptowaluta o tej nazwie już istnieje.',
      'e.BAD_VOLATILITY': 'Zmienność: 0.5 ≤ min ≤ max ≤ 2.0 (np. 0.96 i 1.04).', 'e.BAD_LIQUIDITY': 'Płynność: od 0 do 1 000 000 000.',
      'e.BAD_ICON': 'Ikona: nazwa materiału Minecraft, np. GOLD_NUGGET.', 'e.NOT_CUSTOM': 'Tę kryptowalutę zdefiniowano w config.yml – edytuj ją tam.',
      'e.IN_USE': 'Gracze mają tę kryptowalutę w portfelach lub zleceniach – nie można jej usunąć.',
      'e.BAD_EVENT': 'Niepoprawne dane eventu (treść 1-300 znaków, waga 0-1000).', 'e.BAD_MULTIPLIER': 'Zmiana ceny: od -95% do +900%.',
      'e.NOT_FOUND': 'Nie znaleziono.', 'e.ORIGIN': 'Niedozwolone źródło żądania.', 'e.BAD_REQUEST': 'Niepoprawne żądanie.'
    },
    en: {
      'tab.market': 'Market', 'tab.ranking': '🏆 Leaderboard', 'tab.admin': '⚙️ Admin',
      'lang.switch': 'PL', 'lang.title': 'Przełącz na polski',
      'cmd.link': '/market link', 'cmd.deposit': '/deposit <amount>',
      'login.title': 'Log in', 'login.hint1': 'Join the server and type the command', 'login.hint2': 'You will get a code valid for a few minutes.',
      'login.code-label': 'Code from the game', 'login.code-ph': 'CODE FROM GAME', 'login.submit': 'Log in',
      'login.note': 'Until you log in you can browse the market, charts and the leaderboard.',
      'wallet.title': 'Your capital', 'wallet.worth': 'Total net worth:', 'withdraw.label': 'Withdraw to the game',
      'withdraw.ph': 'Amount', 'withdraw.submit': 'Withdraw to the game', 'withdraw.hint1': 'To deposit into your market account use the command', 'withdraw.hint2': 'in game.',
      'portfolio.title': 'Your portfolio', 'orders.title': '⏲ Your limit orders', 'news.title': '📰 Market news',
      'coins.aria': 'Cryptocurrencies',
      'buy.title': 'Buy (market)', 'sell.title': 'Sell (market)', 'max': 'Max:', 'have': 'You have:', 'pcs': 'pcs.',
      'qty.ph': 'Quantity', 'qty.buy-aria': 'Quantity to buy', 'qty.sell-aria': 'Quantity to sell', 'qty.aria': 'Quantity',
      'buy.btn': 'BUY', 'sell.btn': 'SELL',
      'limit.title': '⏲ Automatic orders (limit)', 'limit.qty-ph': 'Quantity (pcs.)', 'limit.buy-ph': 'When it drops to', 'limit.sell-ph': 'When it rises to',
      'limit.price-aria': 'Limit price', 'limit.buy-btn': 'Buy limit', 'limit.sell-btn': 'Sell limit',
      'lines.label': '📏 Lines:', 'lines.price-ph': 'Price', 'lines.price-aria': 'Line price', 'lines.support': '+ Support', 'lines.resist': '+ Resistance', 'lines.clear': '🗑 Clear',
      'tf.aria': 'Candle interval', 'chart.hint': 'Click the chart to pick a price. Mouse wheel – zoom, drag – pan, double-click – reset view.',
      'chart.aria': 'Candlestick chart', 'chart.empty': 'No chart data',
      'history.title': 'Your history', 'history.type': 'Type', 'history.qty': 'Quantity', 'history.rate': 'Price per pc.', 'history.amount': 'Amount', 'history.time': 'Time',
      'live.title': 'Live',
      'ranking.title': '🏆 Richest investors leaderboard', 'ranking.place': 'Place', 'ranking.player': 'Player', 'ranking.worth': 'Total net worth',
      'footer': 'Currencies, prices and wallets exist only in the game. The market contains automated bots and random events. This is not a real exchange or a financial service.',
      'fee.note': 'Selling fee: {fee}%. Large orders move the price – the cost preview shows the exact amount.',
      'stats': '24h: min {min} · max {max} · sell price {sell}', 'h24': '(24h)',
      'pl.label': 'Your profit/loss: {diff} {cur} ({pct})',
      'news.empty': 'No news.', 'feed.empty': 'Nothing has happened yet.',
      'feed.bought': 'bought', 'feed.sold': 'sold', 'bot': 'bot',
      'wallet.balance-opt': 'Balance ({cur})',
      'user.player': 'Player: ', 'user.logout': 'Log out',
      'portfolio.empty': "You don't own any cryptocurrencies yet.", 'portfolio.value': 'value',
      'orders.empty': 'No active orders.', 'order.buy': 'BUY', 'order.sell': 'SELL', 'order.when-le': 'when price ≤', 'order.when-ge': 'when price ≥', 'order.cancel': 'Cancel',
      'history.none': 'No transactions.', 'history.buy': 'BUY', 'history.sell': 'SELL',
      'quote.buy': 'Cost: {value} {cur} (avg. {avg}, price after purchase {after})',
      'quote.sell': 'You will get: {net} {cur} (fee {fee}, price after sale {after})',
      'line.support': 'Support', 'line.resist': 'Resistance', 'line.remove': 'Remove line',
      'line.bad': 'Enter the line price, e.g. 120.50', 'line.zero': 'The price must be greater than zero.',
      'toast.login-first': 'Log in to trade.', 'toast.logged': 'Logged in as {name}',
      'toast.withdraw': 'Withdrawal queued – the funds will arrive in your in-game account shortly.',
      'toast.bought': 'Bought {qty} {coin} for {value} {cur}', 'toast.sold': 'Sold {qty} {coin} for {value} {cur}',
      'toast.order-saved': 'Limit order saved.', 'toast.order-cancelled': 'Order cancelled.',
      'err.network': 'No connection to the server.', 'err.http': 'Error {status}', 'err.unknown': 'Unexpected error.',
      'admin.title': '⚙️ Economy command center',
      'admin.price.title': '1. Change price', 'admin.coin-aria': 'Currency', 'admin.mode-aria': 'Mode',
      'admin.pump': 'PUMP (+ %)', 'admin.dump': 'DUMP (− %)', 'admin.set': 'SET PRICE', 'admin.value-ph': 'e.g. 20', 'admin.do': 'Execute 🚀',
      'admin.trend.title': '2. Silent trend', 'admin.trend.hint': 'A hidden bonus applied at the next market update.',
      'admin.trend.ph': 'e.g. 40 or -30', 'admin.trend.btn': 'Load trend 🤫',
      'admin.force.title': '3. Force update', 'admin.force.hint': 'Immediately runs the standard price cycle.', 'admin.force.btn': 'Refresh market 🔄',
      'admin.reset.title': '4. Market reset', 'admin.reset.hint': 'Prices return to their starting values, history and news are cleared. Limit orders are refunded.',
      'admin.reset.wipe': 'Also delete wallets and zero player balances', 'admin.reset.ph': 'Type RESET to confirm', 'admin.reset.btn': 'RESET 💀',
      'admin.player.title': '5. Player accounts', 'admin.player.nick': 'Player name', 'admin.asset-aria': 'Asset', 'admin.op-aria': 'Operation',
      'admin.op.add': 'Add (+)', 'admin.op.sub': 'Subtract (−)', 'admin.op.set': 'Set (=)', 'admin.player.qty-ph': 'Amount', 'admin.player.btn': 'Execute',
      'admin.events.title': '6. Market events',
      'admin.events.hint': 'All events (from config.yml and custom ones). “Run” fires the event immediately; events with weight > 0 also appear at random.',
      'admin.events.import': 'Import default events', 'admin.events.import-hint': 'Adds the missing default events (a bull and a bear event for every currency) to your custom events.',
      'admin.events.col-type': 'Kind', 'admin.events.col-coin': 'Currency', 'admin.events.col-change': 'Change', 'admin.events.col-weight': 'Weight', 'admin.events.col-text': 'Text',
      'admin.events.run': 'Run', 'admin.events.delete': 'Delete', 'admin.events.none': 'No events.', 'admin.events.src-config': 'config.yml', 'admin.events.src-custom': 'custom',
      'admin.events.new': 'New event',
      'admin.events.kind-bull': 'BULL (rise)', 'admin.events.kind-bear': 'BEAR (fall)', 'admin.events.kind-info': 'INFO (message only)',
      'admin.events.kind-aria': 'Event kind', 'admin.events.nocoin': '— no currency (message only) —',
      'admin.events.change-ph': 'Price change in %, e.g. 30 or -25', 'admin.events.weight-ph': 'Random weight (0 = manual only)',
      'admin.events.text-ph': 'Message shown to players (max. 300 characters)', 'admin.events.add': 'Add event',
      'admin.events.added': 'Event added.', 'admin.events.deleted': 'Event deleted.', 'admin.events.imported': 'Default events added: {n}.', 'admin.events.ran': 'Event fired.',
      'admin.events.confirm-delete': 'Delete this event?',
      'admin.coins.title': '7. Cryptocurrencies',
      'admin.coins.hint': 'Custom cryptocurrencies work immediately, no restart needed. Currencies from config.yml cannot be edited here.',
      'admin.coins.col-coin': 'Currency', 'admin.coins.col-start': 'Start price', 'admin.coins.col-vol': 'Volatility', 'admin.coins.col-liq': 'Liquidity', 'admin.coins.col-src': 'Source',
      'admin.coins.edit': 'Edit', 'admin.coins.delete': 'Delete', 'admin.coins.new': 'New cryptocurrency', 'admin.coins.editing': 'Editing: {id}',
      'admin.coins.id-ph': 'Name (2-24 chars: letters, digits, _)', 'admin.coins.price-ph': 'Start price, e.g. 120.50',
      'admin.coins.vmin-ph': 'Volatility min (e.g. 0.96)', 'admin.coins.vmax-ph': 'Volatility max (e.g. 1.04)',
      'admin.coins.liq-ph': 'Liquidity (optional, 0 = auto)', 'admin.coins.icon-ph': 'Icon (e.g. DIAMOND)',
      'admin.coins.add': 'Add cryptocurrency', 'admin.coins.save': 'Save changes', 'admin.coins.cancel': 'Cancel editing',
      'admin.coins.help': 'Volatility is the range of the price multiplier in every market cycle (0.96–1.04 = ±4%). Liquidity: how many units move the price by about 1% (0 = calculated automatically). The start price is also used by market resets; when editing, the current price does not change.',
      'admin.coins.added': 'Cryptocurrency added.', 'admin.coins.saved': 'Saved.', 'admin.coins.deleted': 'Cryptocurrency deleted.',
      'admin.coins.confirm-delete': 'Delete cryptocurrency {id}? This cannot be undone.',
      'admin.coins.src-config': 'config.yml', 'admin.coins.src-custom': 'custom',
      'admin.done.price': 'Price changed.', 'admin.done.trend': 'Silent trend loaded.', 'admin.done.reset': 'The market has been reset.',
      'admin.done.player': 'Saved.', 'admin.done.force': 'Market updated.', 'admin.reset.type': 'Type exactly RESET.',
      'e.NO_COIN': 'No such cryptocurrency.', 'e.UNAUTHORIZED': 'Log in with a code from the game.', 'e.BAD_CODE': 'Wrong or expired code. Type {cmd} in game and try again.',
      'e.FORBIDDEN': 'Administrator permission required.', 'e.RATE_LIMIT': 'Too many requests – wait a moment.',
      'e.INVALID_AMOUNT': 'Invalid quantity or amount.', 'e.INVALID_PRICE': 'Invalid price.', 'e.INSUFFICIENT_FUNDS': 'Insufficient funds on your market account.',
      'e.INSUFFICIENT_HOLDINGS': "You don't have that much in your portfolio.", 'e.PRICE_MOVED': 'The price changed in the meantime. Try again.',
      'e.TOO_LARGE': 'The order is too large.', 'e.TOO_SMALL': 'The order is too small.', 'e.TOO_MANY_ORDERS': 'You have too many active orders.',
      'e.NO_ORDER': 'No such order.', 'e.NO_ACCOUNT': 'No such player on the market (they must open the market once).', 'e.CONFIRM': 'Type RESET to confirm.',
      'e.BAD_NAME': 'Name: 2-24 characters, letters, digits and _ only.', 'e.EXISTS': 'A cryptocurrency with this name already exists.',
      'e.BAD_VOLATILITY': 'Volatility: 0.5 ≤ min ≤ max ≤ 2.0 (e.g. 0.96 and 1.04).', 'e.BAD_LIQUIDITY': 'Liquidity: from 0 to 1,000,000,000.',
      'e.BAD_ICON': 'Icon: a Minecraft material name, e.g. GOLD_NUGGET.', 'e.NOT_CUSTOM': 'This cryptocurrency is defined in config.yml – edit it there.',
      'e.IN_USE': 'Players hold this cryptocurrency or have orders for it – it cannot be removed.',
      'e.BAD_EVENT': 'Invalid event data (text 1-300 characters, weight 0-1000).', 'e.BAD_MULTIPLIER': 'Price change: from -95% to +900%.',
      'e.NOT_FOUND': 'Not found.', 'e.ORIGIN': 'Request origin not allowed.', 'e.BAD_REQUEST': 'Invalid request.'
    }
  };

  function readStored() { try { return localStorage.getItem('kg_lang'); } catch (e) { return null; } }
  function writeStored(v) { try { localStorage.setItem('kg_lang', v); } catch (e) { /* tryb prywatny */ } }

  let lang = readStored();
  if (lang !== 'pl' && lang !== 'en') lang = null;     // brak wyboru -> język serwera (setServerDefault)
  let serverDefault = null;

  function current() { return lang || serverDefault || (String(navigator.language || '').toLowerCase().startsWith('pl') ? 'pl' : 'en'); }

  function t(key, vars) {
    let s = (D[current()] && D[current()][key]) || D.pl[key] || key;
    if (vars) for (const k of Object.keys(vars)) s = s.split('{' + k + '}').join(String(vars[k]));
    return s;
  }

  function apply(root) {
    const r = root || document;
    r.querySelectorAll('[data-i18n]').forEach(n => { n.textContent = t(n.dataset.i18n); });
    r.querySelectorAll('[data-i18n-ph]').forEach(n => { n.setAttribute('placeholder', t(n.dataset.i18nPh)); });
    r.querySelectorAll('[data-i18n-aria]').forEach(n => { n.setAttribute('aria-label', t(n.dataset.i18nAria)); });
    r.querySelectorAll('[data-i18n-title]').forEach(n => { n.setAttribute('title', t(n.dataset.i18nTitle)); });
    document.documentElement.lang = current();
  }

  const listeners = [];
  function set(l) {
    if (l !== 'pl' && l !== 'en') return;
    lang = l; writeStored(l); apply(); listeners.forEach(f => f(l));
  }

  window.I18N = {
    t, apply, set, lang: current,
    onChange(f) { listeners.push(f); },
    setServerDefault(l) {
      const nl = (l === 'en' || l === 'pl') ? l : null;
      if (nl && nl !== serverDefault) { const before = current(); serverDefault = nl; if (!lang && current() !== before) { apply(); listeners.forEach(f => f(current())); } }
    }
  };
})();
