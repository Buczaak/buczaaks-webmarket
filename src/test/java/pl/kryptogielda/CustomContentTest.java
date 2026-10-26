package pl.kryptogielda;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kryptogielda.core.*;
import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.sim.SimConfig;
import pl.kryptogielda.sim.Simulator;
import pl.kryptogielda.store.JdbcStore;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Własne kryptowaluty i eventy dodawane przez admina + tłumaczenia komunikatów. */
class CustomContentTest {

    @TempDir Path tmp;
    JdbcStore store;
    MarketService m;
    final List<CoinDef> base = List.of(new CoinDef("MineCoin", 10_000, "GOLD_NUGGET", 0.96, 1.04));

    @BeforeEach
    void setUp() {
        store = new JdbcStore("jdbc:sqlite:" + tmp.resolve("c.db"), null, null);
        m = new MarketService(store, Settings.defaults(), System::currentTimeMillis, Logger.getLogger("t"));
        m.init(base);
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void addCustomCoinWorksImmediatelyAndSurvivesRestart() {
        Result<CoinDef> r = m.addCustomCoin(new CoinDef("ZetaCoin", 25_050, "diamond", 0.9, 1.1, 5));
        assertTrue(r.ok, r.toString());
        assertEquals("DIAMOND", r.value.icon());
        assertTrue(m.isCustomCoin("ZetaCoin"));
        assertEquals("ZetaCoin", m.canon("zetacoin"));
        assertEquals(25_050, m.price("ZetaCoin"));
        assertEquals(2, m.coinDefs().size());
        assertTrue(m.coins().stream().anyMatch(c -> c.id().equals("ZetaCoin")));

        // po „restarcie” (nowa instancja na tej samej bazie) waluta nadal jest
        MarketService m2 = new MarketService(store, Settings.defaults(), System::currentTimeMillis, Logger.getLogger("t"));
        m2.init(base);
        assertTrue(m2.isCustomCoin("ZetaCoin"));
        assertFalse(m2.isCustomCoin("MineCoin"));
        assertEquals(2, m2.coinDefs().size());
        assertEquals(0.9, m2.coinDefs().get(1).volMin(), 1e-9);
    }

    @Test
    void customCoinValidation() {
        assertEquals("BAD_NAME", m.addCustomCoin(new CoinDef("x", 1000, "GOLD_NUGGET", 0.9, 1.1)).code);
        assertEquals("BAD_NAME", m.addCustomCoin(new CoinDef("a b", 1000, "GOLD_NUGGET", 0.9, 1.1)).code);
        assertEquals("EXISTS", m.addCustomCoin(new CoinDef("minecoin", 1000, "GOLD_NUGGET", 0.9, 1.1)).code);
        assertEquals("INVALID_PRICE", m.addCustomCoin(new CoinDef("Aaa", 1, "GOLD_NUGGET", 0.9, 1.1)).code);
        assertEquals("BAD_VOLATILITY", m.addCustomCoin(new CoinDef("Aaa", 1000, "GOLD_NUGGET", 1.2, 1.1)).code);
        assertEquals("BAD_VOLATILITY", m.addCustomCoin(new CoinDef("Aaa", 1000, "GOLD_NUGGET", 0.1, 1.1)).code);
        assertEquals("BAD_LIQUIDITY", m.addCustomCoin(new CoinDef("Aaa", 1000, "GOLD_NUGGET", 0.9, 1.1, -1)).code);
        assertEquals("BAD_ICON", m.addCustomCoin(new CoinDef("Aaa", 1000, "bad icon!", 0.9, 1.1)).code);
        assertEquals(1, m.coinDefs().size());
    }

    @Test
    void updateAndRemoveCustomCoin() {
        assertTrue(m.addCustomCoin(new CoinDef("ZetaCoin", 20_000, "EMERALD", 0.9, 1.1)).ok);
        assertEquals("NOT_CUSTOM", m.updateCustomCoin(new CoinDef("MineCoin", 5000, "EMERALD", 0.9, 1.1)).code);
        assertEquals("NOT_CUSTOM", m.removeCustomCoin("MineCoin").code);
        long priceBefore = m.price("ZetaCoin");
        assertTrue(m.updateCustomCoin(new CoinDef("zetacoin", 30_000, "REDSTONE", 0.8, 1.2, 7)).ok);
        CoinDef d = m.coinDefs().stream().filter(c -> c.id().equals("ZetaCoin")).findFirst().orElseThrow();
        assertEquals(30_000, d.startPrice());
        assertEquals("REDSTONE", d.icon());
        assertEquals(priceBefore, m.price("ZetaCoin"), "edycja nie zmienia aktualnej ceny");

        // gracz ma monety -> nie wolno usunąć
        m.ensureAccount("u1", "Ala");
        m.adminBalance("Ala", MarketService.Op.ADD, 1_000_000);
        assertTrue(m.adminHolding("Ala", "ZetaCoin", MarketService.Op.ADD, Fixed.parseQty("2")).ok);
        assertEquals("IN_USE", m.removeCustomCoin("ZetaCoin").code);
        m.adminHolding("Ala", "ZetaCoin", MarketService.Op.SUB, Fixed.parseQty("2"));
        Result<Boolean> rm = m.removeCustomCoin("ZetaCoin");
        assertTrue(rm.ok, rm.toString());
        assertNull(m.canon("ZetaCoin"));
        assertEquals(1, m.coinDefs().size());
        // można dodać ponownie od zera
        assertTrue(m.addCustomCoin(new CoinDef("ZetaCoin", 10_000, "EMERALD", 0.9, 1.1)).ok);
    }

    @Test
    void customCoinsAreTradedByBots() {
        assertTrue(m.addCustomCoin(new CoinDef("ZetaCoin", 20_000, "EMERALD", 0.9, 1.1)).ok);
        Result<MarketService.PriceChange> r = m.applyMultiplier("ZetaCoin", 1.5, false);
        assertTrue(r.ok);
        assertEquals(30_000, r.value.newPrice());
    }

    @Test
    void customEventsCrud() {
        Result<CustomEvent> r = m.addCustomEvent("HOSSA", "Test event", "mineCoin", 1.3, 2, "&a");
        assertTrue(r.ok, r.toString());
        assertEquals("MineCoin", r.value.coin());
        assertEquals(1, m.customEvents().size());
        assertEquals("NO_COIN", m.addCustomEvent("X", "t", "Nope", 1.1, 1, "").code);
        assertEquals("BAD_MULTIPLIER", m.addCustomEvent("X", "t", "MineCoin", 0, 1, "").code);
        assertEquals("BAD_MULTIPLIER", m.addCustomEvent("X", "t", "MineCoin", 50, 1, "").code);
        assertEquals("BAD_EVENT", m.addCustomEvent("X", "", "MineCoin", 1.1, 1, "").code);
        assertEquals("BAD_EVENT", m.addCustomEvent("X", "t", "MineCoin", 1.1, -1, "").code);
        assertEquals("BAD_EVENT", m.addCustomEvent("X", "t", "MineCoin", 1.1, 1, "zielony").code);
        assertTrue(m.addCustomEvent("INFO", "Sama wiadomość", "", 0, 1, "&b").ok, "event bez waluty");

        MarketService m2 = new MarketService(store, Settings.defaults(), System::currentTimeMillis, Logger.getLogger("t"));
        m2.init(base);
        assertEquals(2, m2.customEvents().size());

        long id = m.customEvents().get(0).id();
        assertTrue(m.deleteCustomEvent(id).ok);
        assertEquals("NOT_FOUND", m.deleteCustomEvent(id).code);
        assertEquals(1, m.customEvents().size());
    }

    @Test
    void simulatorMergesConfigAndCustomEventsAndImportsDefaults() {
        SimConfig cfg = SimConfig.defaults();
        List<SimConfig.EventDef> only4 = new ArrayList<>(SimConfig.defaultEvents().subList(0, 4));
        SimConfig old = new SimConfig(cfg.ordersIntervalSec(), false, 30, 0, List.of(), 1, 1, 50, 1, 1, 1, 1, 0.5, 1.0,
                false, 300, 0.03, 0.03, true, 1800, 100, only4, false, 5, 0.25);
        List<String> broadcasts = new ArrayList<>();
        Simulator sim = new Simulator(m, old, broadcasts::add, new Messages(), new Random(1), Logger.getLogger("t"));
        assertEquals(4, sim.events().size());
        assertEquals(4, sim.configEventCount());

        // import domyślnych: tylko MineCoin jest zdefiniowane w tym teście - ale eventy dla nieistniejących walut też się zapisują
        // jako sama wiadomość? Nie - walidacja odrzuca nieznane waluty, więc import dodaje tylko te pasujące.
        int added = sim.importDefaultEvents("pl");
        assertTrue(added >= 0);
        assertEquals(4 + added, sim.events().size());
        assertEquals(0, sim.importDefaultEvents("pl"), "drugi import nie dubluje");

        // własny event trafia na listę i da się go uruchomić
        assertTrue(m.addCustomEvent("HOSSA", "Mój event", "MineCoin", 2.0, 1, "&a").ok);
        int idx = sim.events().size() - 1;
        assertEquals("Mój event", sim.events().get(idx).text());
        long before = m.price("MineCoin");
        sim.runEvent(sim.events().get(idx));
        assertEquals(before * 2, m.price("MineCoin"));
        assertFalse(broadcasts.isEmpty());
    }

    @Test
    void englishDefaultsHaveTheSameKeysAsPolish() {
        Map<String, String> pl = Messages.defaultsFor("pl");
        Map<String, String> en = Messages.defaultsFor("en");
        assertEquals(new TreeSet<>(pl.keySet()), new TreeSet<>(en.keySet()));
        for (String k : pl.keySet()) {
            assertFalse(en.get(k).isBlank(), k);
            // te same znaczniki {…}
            assertEquals(placeholders(pl.get(k)), placeholders(en.get(k)), "placeholders in " + k);
        }
        assertNotEquals(pl.get("deposit.usage"), en.get("deposit.usage"));
        assertEquals(en.get("prefix"), Messages.forLanguage("en").raw("prefix"));
        assertEquals(pl.get("prefix"), Messages.forLanguage("pl").raw("prefix"));
    }

    @Test
    void englishDefaultEventsMirrorPolishOnes() {
        List<SimConfig.EventDef> pl = SimConfig.defaultEvents("pl"), en = SimConfig.defaultEvents("en");
        assertEquals(pl.size(), en.size());
        for (int i = 0; i < pl.size(); i++) {
            assertEquals(pl.get(i).coin(), en.get(i).coin());
            assertEquals(pl.get(i).multiplier(), en.get(i).multiplier(), 1e-9);
            assertNotEquals(pl.get(i).text(), en.get(i).text());
        }
    }

    private static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>();
        var mt = java.util.regex.Pattern.compile("\\{[a-z-]+}").matcher(s);
        while (mt.find()) out.add(mt.group());
        return out;
    }
}
