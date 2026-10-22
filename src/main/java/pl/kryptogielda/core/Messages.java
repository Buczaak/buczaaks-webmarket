package pl.kryptogielda.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Teksty wyświetlane graczom (czat, GUI, komendy). Domyślnie po polsku; każdy klucz można
 * nadpisać w pliku messages.yml. Znaczniki: {nazwa} zastępowane wartościami, kody kolorów przez &amp;.
 */
public final class Messages {

    private final Map<String, String> map = new HashMap<>(DEFAULTS);

    public static final Map<String, String> DEFAULTS = new HashMap<>();

    private static void d(String k, String v) { DEFAULTS.put(k, v); }

    static {
        // --- czat: transakcje
        d("chat.trade.buy", "&8[&aGiełda&8] &aGracz &f{player} &aKUPUJE &f{qty} szt. &e{coin} &aza &a{value} {cur}!");
        d("chat.trade.sell", "&8[&cGiełda&8] &cGracz &f{player} &cSPRZEDAJE &f{qty} szt. &e{coin} &aza &c{value} {cur}!");
        d("chat.limit.buy", "&8[&eZlecenie Limit&8] &aGracz &f{player} &aautomatycznie KUPUJE &f{qty} szt. &e{coin} &aza &a{value} {cur}!");
        d("chat.limit.sell", "&8[&eZlecenie Limit&8] &cGracz &f{player} &cautomatycznie SPRZEDAJE &f{qty} szt. &e{coin} &aza &c{value} {cur}!");
        d("chat.bot.buy", "&8[&eGiełda-Bot&8] &a{player} POMPUJE &e{coin} &a(Wartość: {value} {cur})!");
        d("chat.bot.sell", "&8[&eGiełda-Bot&8] &c{player} ZRZUCA &e{coin} &c(Wartość: {value} {cur})!");
        // --- czat: cykl rynku
        d("update.title", "&8[&6Giełda&8] &e&lAktualizacja Rynku Kryptowalut!");
        d("update.trend", "&8 » &7Trend ogólnoserwerowy: {trend}");
        d("update.coin", "&8 » &f{coin}: &6{price} {cur} {change}");
        d("trend.flat", "&7Stabilny ➔");
        d("trend.up", "&aZwiększona Płynność (Hossa ↗)");
        d("trend.down", "&cKryzys Płynności (Bessa ↘)");
        d("change.up", "&a(+{diff} {cur})");
        d("change.down", "&c({diff} {cur})");
        d("change.flat", "&7(0 {cur})");
        d("event.line", "&8[&6Giełda - {type}&8] {color}{text}");
        d("reset.broadcast", "&8[&c&lGIEŁDA&8] &4&lUWAGA! &cRynek kryptowalut został wyzerowany! Zaczynamy nową erę!");
        // --- gra: ogólne
        d("prefix", "&8[&6Giełda&8] ");
        d("err.no-permission", "&cNie masz uprawnień do tej komendy.");
        d("err.player-only", "&cTa komenda jest dostępna tylko dla graczy.");
        d("err.no-economy", "&cEkonomia serwera (Vault) jest niedostępna. Spróbuj później.");
        d("err.busy", "&cGiełda jest chwilowo zajęta, spróbuj ponownie.");
        d("err.internal", "&cWystąpił błąd giełdy. Powiadom administratora.");
        d("err.invalid-number", "&cPodaj poprawną liczbę (np. 100 albo 12.5).");
        d("ok.bought", "&aZakupiono &e{qty}x {coin} &aza &c{value} {cur}&a.");
        d("ok.sold", "&aSprzedano &e{qty}x {coin} &aza &a+{value} {cur}&a.");
        d("err.not-enough-money", "&cBrak kapitału! Potrzebujesz &e{value} {cur}&c.");
        d("err.cant-afford-one", "&cNie stać cię na ani jedną sztukę!");
        d("err.not-enough-assets", "&cNie posiadasz wystarczająco aktywów w portfelu!");
        // --- gra: wpłaty / wypłaty / link
        d("deposit.ok", "&aWpłacono &e{value} {cur} &ana konto giełdowe. Saldo: &e{balance} {cur}&a.");
        d("deposit.not-enough", "&cNie masz tylu środków na serwerze!");
        d("deposit.usage", "&7Użycie: &f/wplac <kwota>");
        d("withdraw.ok", "&aWypłacono &e{value} {cur} &ado gry.");
        d("withdraw.usage", "&7Użycie: &f/wyplac <kwota>");
        d("withdraw.failed", "&cWypłata nie powiodła się - środki wróciły na konto giełdowe.");
        d("link.code", "&aKod logowania na stronę: &e&l{code} &7(ważny {minutes} min.)");
        d("link.url", "&aStrona giełdy: &b{url}");
        d("link.disabled", "&cStrona WWW jest wyłączona w konfiguracji.");
        d("balance.line", "&7Konto giełdowe: &e{balance} {cur}&7, kapitał całkowity: &e{worth} {cur}");
        // --- dodatkowe
        d("err.price-moved", "&cCena zmieniła się w trakcie transakcji - spróbuj ponownie. Środki zostały zwrócone.");
        d("err.generic", "&c{message}");
        d("err.unknown-coin", "&cNie ma takiej kryptowaluty.");
        d("err.no-account", "&cNie masz jeszcze konta giełdowego.");
        d("err.deposit-failed", "&cWypłata ze środków serwera nie powiodła się.");
        d("ok.sold-refunded", "&cNie udało się wypłacić środków do gry - kwota {value} {cur} trafiła na Twoje konto giełdowe.");
        d("ok.refund-diff", "&7Zwrócono różnicę: &e{value} {cur}&7.");
        d("withdraw.queued", "&aWypłata &e{value} {cur} &azlecona - środki pojawią się za chwilę.");
        d("help.header", "&8[&6Giełda&8] &eKomendy:");
        d("help.menu", "&f/gielda &7- otwiera menu giełdy");
        d("help.link", "&f/gielda link &7- kod logowania na stronę WWW");
        d("help.wallet", "&f/gielda portfel &7- Twoje kryptowaluty");
        d("help.market", "&f/gielda rynek &7- aktualne notowania");
        d("help.balance", "&f/gielda saldo &7- stan konta giełdowego");
        d("help.deposit", "&f/wplac <kwota> &7- przelej pieniądze z gry na konto giełdowe");
        d("help.withdraw", "&f/wyplac <kwota> &7- wypłać z konta giełdowego do gry");
        d("gui.main.portfolio", "&b&lTwój portfel");
        d("gui.main.portfolio.lore", "&7Kryptowaluty, które posiadasz.");
        d("gui.main.market", "&e&lRynek");
        d("gui.main.market.lore", "&7Aktualne notowania i handel.");
        d("gui.main.web", "&a&lStrona WWW");
        d("gui.main.web.lore", "&7Wykresy i zlecenia limit: &f/gielda link");
        d("gui.coin.name", "&e&l{coin}");
        d("gui.coin.click", "&e[Kliknij, aby otworzyć panel handlowy]");
        d("gui.coin.holding", "&7Posiadany wolumen: &b{qty} szt.");
        d("gui.coin.value", "&7Obecna wartość: &6{value} {cur}");
        d("gui.coin.cost", "&7Łącznie zainwestowano: &c{cost} {cur}");
        d("gui.coin.pl", "&7Niezrealizowany wynik: {color}{diff} {cur}");
        d("gui.coin.trend", "&7Aktualny trend: &f{trend}");
        d("gui.coin.buy-price", "&7Cena kupna: &c{price} {cur}");
        d("gui.coin.sell-price", "&7Cena sprzedaży: &a{price} {cur}");
        d("gui.portfolio.empty", "&7Nie masz jeszcze żadnych kryptowalut.");
        d("gui.broker.buy", "&a&lKUP {amount}");
        d("gui.broker.buy-max", "&a&lKUP MAX");
        d("gui.broker.sell", "&c&lSPRZEDAJ {amount}");
        d("gui.broker.sell-all", "&c&lSPRZEDAJ WSZYSTKO");
        d("gui.broker.wallet", "&6Twoje środki: &e{balance} {cur}");
        d("gui.broker.holding", "&bPosiadasz: &f{qty} szt. &7(wartość {value} {cur})");
        d("gui.broker.fee", "&7Prowizja przy sprzedaży: &f{fee}%");
        d("admin.ok", "&aGotowe.");
        d("admin.usage", "&7Użycie: &f{usage}");
        // --- gui
        d("gui.main.title", "&8» &6Giełda Kryptowalut &8«");
        d("gui.portfolio.title", "&8» &bTwój Portfel Krypto &8«");
        d("gui.market.title", "&8» &eRynek Krypto (Live) &8«");
        d("gui.broker.title", "&8» &6Broker: {coin} &8«");
        d("gui.back", "&cPowrót");
        d("gui.back.main", "&7Wróć do głównego menu giełdy.");
        d("gui.back.market", "&cPowrót do Rynku");
    }

    public Messages() {}

    public void put(String key, String value) {
        if (key != null && value != null) map.put(key, value);
    }

    public String raw(String key) { return map.getOrDefault(key, key); }

    /** Pobiera tekst i podstawia pary klucz/wartość: format("x", "player", "Ala", "qty", "5"). */
    public String format(String key, Object... kv) {
        String s = raw(key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }
}
