package pl.kryptogielda.sim;

import java.util.List;

/**
 * Ustawienia symulacji rynku (boty, cykl cen, eventy). Wartości w groszach / procentach / sekundach.
 */
public record SimConfig(
        int ordersIntervalSec,
        boolean botsEnabled,
        int botIntervalSec,
        int botChancePct,
        List<String> bots,
        long botBudgetMin,
        long botBudgetMax,
        int botBuyChancePct,
        double botBuyMultMin,
        double botBuyMultMax,
        double botSellMultMin,
        double botSellMultMax,
        double botSellFractionMin,
        double botSellFractionMax,
        boolean simEnabled,
        int simIntervalSec,
        double marketMoodMax,
        double meanReversion,
        boolean eventsEnabled,
        int eventIntervalSec,
        int eventChancePct,
        List<EventDef> events,
        boolean microEnabled,
        int microIntervalSec,
        double microMaxPercent
) {
    public record EventDef(String type, String text, String coin, double multiplier, int weight, String color) {}

    public static SimConfig defaults() {
        return new SimConfig(10,
                true, 30, 60, List.of("Whale_Alert", "DiamondHandz", "Moon_Boy", "Paper_Hands", "HODL_King", "Satoshi_Steve", "Crypto_Creeper", "Block_Baron", "Ender_Whale", "Pickaxe_Capital", "Redstone_Ventures", "Nether_Hedge_Fund", "Wolf_Of_Blockchain", "Trader_PRO", "Lazy_Alpaca", "Golem_Capital", "Villager_Bank", "Bull_Run_Bob", "Bear_Hugger", "Sigma_Trader"),
                5_000_000, 80_000_000, 55, 1.01, 1.07, 0.92, 0.99, 0.3, 1.0,
                true, 300, 0.03, 0.03,
                true, 1800, 60, defaultEvents(),
                true, 5, 0.25);
    }

    /** Dla każdej kryptowaluty po jednym evencie hossy i jednym bessy. */
    public static List<EventDef> defaultEvents(String lang) {
        return "en".equalsIgnoreCase(lang) ? defaultEventsEn() : defaultEvents();
    }

    public static List<EventDef> defaultEventsEn() {
        return List.of(
                new EventDef("BULL", "A giant gold vein under spawn! Mining output soars and MineCoin gets pricier!", "MineCoin", 1.35, 1, "&a"),
                new EventDef("BEAR", "A massive MineCoin dupe detected! Cheap coins flood the market and the price drops!", "MineCoin", 0.70, 1, "&c"),
                new EventDef("BULL", "A new End city discovered! Demand for Enderium rises sharply.", "Enderium", 1.40, 1, "&a"),
                new EventDef("BEAR", "An army of Endermen dumps Enderium from their wallets! Market panic.", "Enderium", 0.65, 1, "&c"),
                new EventDef("BULL", "Nether Bank announces full reserve backing. Trust in NetherTether grows!", "NetherTether", 1.20, 1, "&a"),
                new EventDef("BEAR", "Rumours of NetherTether losing its backing! The stablecoin loses its peg.", "NetherTether", 0.75, 1, "&c"),
                new EventDef("BULL", "A viral CreepDoge meme! Crowds rush to buy!", "CreepDoge", 1.45, 1, "&a"),
                new EventDef("BEAR", "A Creeper blew up the CreepDoge chest! Investors panic-sell.", "CreepDoge", 0.60, 1, "&c"),
                new EventDef("BULL", "New diamond deposits found. DiamondHands surges in value!", "DiamondHands", 1.25, 1, "&a"),
                new EventDef("BEAR", "Whales are taking profits! A huge DiamondHands sell-off.", "DiamondHands", 0.72, 1, "&c"),
                new EventDef("BULL", "A brilliant redstone design speeds up the RedstoneChain network! Interest grows.", "RedstoneChain", 1.30, 1, "&a"),
                new EventDef("BEAR", "A critical bug found in RedstoneChain! Investors dump their assets!", "RedstoneChain", 0.60, 1, "&c"),
                new EventDef("BULL", "Slimes everywhere now accept SlimeToken as payment! The price jumps up.", "SlimeToken", 1.35, 1, "&a"),
                new EventDef("BEAR", "The slime chunk was shut down! Demand for SlimeToken collapses.", "SlimeToken", 0.65, 1, "&c"),
                new EventDef("BULL", "Top players switch to WardenCoin. It becomes an elite currency!", "WardenCoin", 1.60, 1, "&a"),
                new EventDef("BEAR", "A Warden awakens in the heart of the Deep Dark! Panic, WardenCoin drops sharply.", "WardenCoin", 0.62, 1, "&c"));
    }

    public static List<EventDef> defaultEvents() {
        return List.of(
                new EventDef("HOSSA", "Gigantyczna żyła złota pod spawnem! Wydobycie rośnie, a MineCoin drożeje!", "MineCoin", 1.35, 1, "&a"),
                new EventDef("BESSA", "Wykryto masowy dupe MineCoin! Rynek zalany tanimi monetami, cena leci w dół!", "MineCoin", 0.70, 1, "&c"),
                new EventDef("HOSSA", "Odkryto nową fortecę Endu! Popyt na Enderium gwałtownie rośnie.", "Enderium", 1.40, 1, "&a"),
                new EventDef("BESSA", "Armia Endermanów wyprzedaje Enderium z portfeli! Panika na rynku.", "Enderium", 0.65, 1, "&c"),
                new EventDef("HOSSA", "Nether Bank ogłasza pełne pokrycie rezerw. Zaufanie do NetherTether rośnie!", "NetherTether", 1.20, 1, "&a"),
                new EventDef("BESSA", "Plotki o utracie pokrycia NetherTether! Stablecoin traci kotwicę.", "NetherTether", 0.75, 1, "&c"),
                new EventDef("HOSSA", "Viralowy mem o CreepDoge! Tłumy rzucają się do zakupów!", "CreepDoge", 1.45, 1, "&a"),
                new EventDef("BESSA", "Creeper wysadził skrzynię z CreepDoge! Inwestorzy w panice sprzedają.", "CreepDoge", 0.60, 1, "&c"),
                new EventDef("HOSSA", "Odkryto nowe złoża diamentów. DiamondHands gwałtownie zyskuje na wartości!", "DiamondHands", 1.25, 1, "&a"),
                new EventDef("BESSA", "Wieloryby realizują zyski! Wielka wyprzedaż DiamondHands.", "DiamondHands", 0.72, 1, "&c"),
                new EventDef("HOSSA", "Genialny układ redstone przyspiesza sieć RedstoneChain! Rośnie zainteresowanie.", "RedstoneChain", 1.30, 1, "&a"),
                new EventDef("BESSA", "Wykryto krytyczny błąd w sieci RedstoneChain! Inwestorzy wyprzedają aktywa!", "RedstoneChain", 0.60, 1, "&c"),
                new EventDef("HOSSA", "Slime'y masowo przyjmują SlimeToken jako płatność! Cena skacze w górę.", "SlimeToken", 1.35, 1, "&a"),
                new EventDef("BESSA", "Wyłączono slime chunk! Popyt na SlimeToken załamuje się.", "SlimeToken", 0.65, 1, "&c"),
                new EventDef("HOSSA", "Najwięksi gracze przechodzą na WardenCoin. To staje się waluta elitarna!", "WardenCoin", 1.60, 1, "&a"),
                new EventDef("BESSA", "Warden obudzony w sercu Głębi! Panika, WardenCoin gwałtownie tanieje.", "WardenCoin", 0.62, 1, "&c"));
    }
}
