package pl.kryptogielda;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kryptogielda.core.*;
import pl.kryptogielda.sim.SimConfig;
import pl.kryptogielda.sim.Simulator;
import pl.kryptogielda.store.JdbcStore;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorTest {

    @TempDir Path tmp;
    JdbcStore store;
    MarketService m;
    Simulator sim;
    final AtomicLong time = new AtomicLong(1_700_000_000_000L);
    final List<String> chat = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new JdbcStore("jdbc:sqlite:" + tmp.resolve("s.db"), null, null);
        m = new MarketService(store, Settings.defaults(), time::get, Logger.getLogger("test"));
        m.init(List.of(
                new CoinDef("MineCoin", 10_000, "GOLD_NUGGET", 0.96, 1.04),
                new CoinDef("CreepDoge", 1_000, "GUNPOWDER", 0.85, 1.15),
                new CoinDef("RedstoneChain", 7_500, "REDSTONE", 0.97, 1.03)));
        sim = new Simulator(m, SimConfig.defaults(), chat::add, new Messages(), new Random(42), Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    void marketUpdateMovesEveryCoinAndBroadcasts() {
        sim.marketUpdate();
        assertTrue(chat.size() >= 5, "tytuł + trend + 3 waluty");
        assertTrue(chat.get(0).contains("Aktualizacja Rynku"));
        long moved = m.coins().stream().filter(c -> c.price() != c.open24h()).count();
        assertTrue(moved >= 1);
        assertTrue(chat.stream().anyMatch(s -> s.contains("MineCoin")));
    }

    @Test
    void silentTrendAppliedOnlyOnce() {
        m.setSilentTrend("MineCoin", 40);
        long before = m.price("MineCoin");
        sim.marketUpdate();
        long after = m.price("MineCoin");
        assertTrue(after > before * 1.2, "cichy trend +40% przebił losowy ruch: " + before + " -> " + after);
        sim.marketUpdate();
        assertTrue(m.price("MineCoin") < after * 1.2, "trend nie powtarza się");
    }

    @Test
    void eventsChangePriceAndPostNews() {
        long before = m.price("RedstoneChain");
        SimConfig.EventDef e = new SimConfig.EventDef("KRACH", "Test krach", "RedstoneChain", 0.60, 1, "&c");
        sim.runEvent(e);
        assertEquals(Math.round(before * 0.6), m.price("RedstoneChain"), 1);
        assertEquals("KRACH", m.news(5).get(0).type());
        assertTrue(chat.get(0).contains("Test krach"));
        assertTrue(chat.get(0).contains("&c"));
    }

    @Test
    void unknownEventCoinStillPostsNews() {
        sim.runEvent(new SimConfig.EventDef("INFO", "Bez waluty", "Nope", 2.0, 1, ""));
        assertEquals(1, m.news(5).size());
        assertEquals(10_000, m.price("MineCoin"));
    }

    @Test
    void tickRunsTasksOnSchedule() {
        // konfiguracja z krótkimi interwałami i 100% szansą
        SimConfig c = new SimConfig(1, true, 2, 100, List.of("Bot1", "Bot2"), 100_000, 200_000, 100, 1.01, 1.02, 0.95, 0.99, 0.5, 1.0,
                true, 5, 0.0, 0.0, true, 8, 100,
                List.of(new SimConfig.EventDef("INFO", "Hej", "MineCoin", 1.1, 1, "&a")),
                false, 5, 0.25);
        sim = new Simulator(m, c, chat::add, new Messages(), new Random(1), Logger.getLogger("test"));
        long t = time.get();
        for (int i = 0; i <= 10; i++) {
            time.set(t + i * 1000L);
            sim.tick(time.get());
        }
        assertFalse(m.recentTrades(50).isEmpty(), "boty handlowały");
        assertTrue(m.recentTrades(50).stream().allMatch(tr -> tr.source().equals("BOT")));
        assertFalse(m.news(5).isEmpty(), "event odpalony");
        assertTrue(chat.stream().anyMatch(s -> s.contains("Aktualizacja")), "cykl cen odpalony");
    }

    @Test
    void tickProcessesLimitOrders() {
        m.deposit("u1", "Ala", Fixed.parseMoney("100000"));
        m.placeLimit("u1", "Ala", Side.BUY, "MineCoin", Fixed.parseQty("5"), 12_000);
        // cena 100 <= limit 120 -> zlecenie powinno się wykonać przy pierwszym przebiegu
        long t = time.get();
        sim.tick(t);
        sim.tick(t + 11_000);
        assertEquals(Fixed.parseQty("5"), m.holdingQty("u1", "MineCoin"));
    }

    @Test
    void priceStaysAnchoredOverLongRun() {
        // 30 dni cyklu cen co 5 minut - ceny nie mogą dryfować do dna ani eksplodować
        SimConfig c = SimConfig.defaults();
        Simulator s = new Simulator(m, c, x -> {}, new Messages(), new Random(7), Logger.getLogger("test"));
        for (int i = 0; i < 30 * 288; i++) { time.addAndGet(300_000); s.marketUpdate(); }
        for (MarketService.CoinView v : m.coins()) {
            CoinDef d = m.coinDefs().stream().filter(x -> x.id().equals(v.id())).findFirst().get();
            double ratio = (double) v.price() / d.startPrice();
            assertTrue(ratio > 0.2 && ratio < 5.0, v.id() + " odpłynął od ceny startowej: x" + ratio);
        }
    }

    @Test
    void microTickMovesPricesWithinScaledBounds() {
        Map<String, Long> before = new HashMap<>();
        for (MarketService.CoinView v : m.coins()) before.put(v.id(), v.price());
        int changed = 0;
        for (int i = 0; i < 50; i++) {
            Map<String, Long> prev = new HashMap<>();
            for (MarketService.CoinView v : m.coins()) prev.put(v.id(), v.price());
            time.addAndGet(5_000);
            sim.microTick();
            for (MarketService.CoinView v : m.coins()) {
                double r = (double) v.price() / prev.get(v.id());
                // domyślnie 0,25% x współczynnik (maks. 3) = 0,75% na jeden ruch
                assertTrue(r > 0.99 && r < 1.01, v.id() + " ruch poza zakresem: " + r);
                if (v.price() != prev.get(v.id())) changed++;
            }
        }
        assertTrue(changed > 20, "ceny powinny się realnie zmieniać, zmian: " + changed);
    }

    @Test
    void microTicksRunOnScheduleAndCanBeDisabled() {
        SimConfig on = new SimConfig(1000, false, 1000, 0, List.of(), 1, 1, 50, 1, 1, 1, 1, 0.5, 1.0,
                false, 1000, 0, 0, false, 1000, 0, List.of(), true, 5, 0.25);
        Simulator s = new Simulator(m, on, x -> {}, new Messages(), new Random(3), Logger.getLogger("test"));
        long t = time.get();
        s.tick(t);
        long p0 = m.price("CreepDoge");
        time.set(t + 6_000);
        s.tick(time.get());
        assertNotEquals(p0, m.price("CreepDoge"), "po 6 s powinien nastąpić mikro-ruch");

        SimConfig off = new SimConfig(1000, false, 1000, 0, List.of(), 1, 1, 50, 1, 1, 1, 1, 0.5, 1.0,
                false, 1000, 0, 0, false, 1000, 0, List.of(), false, 5, 0.25);
        Simulator s2 = new Simulator(m, off, x -> {}, new Messages(), new Random(3), Logger.getLogger("test"));
        long p1 = m.price("CreepDoge");
        long t2 = time.get();
        s2.tick(t2);
        time.set(t2 + 60_000);
        s2.tick(time.get());
        assertEquals(p1, m.price("CreepDoge"));
    }

    @Test
    void priceStaysAnchoredWithMicroTicks() {
        SimConfig c = SimConfig.defaults();
        Simulator s = new Simulator(m, c, x -> {}, new Messages(), new Random(11), Logger.getLogger("test"));
        for (int i = 0; i < 7 * 288; i++) {
            time.addAndGet(300_000);
            s.marketUpdate();
            s.microTick(); s.microTick();
        }
        for (MarketService.CoinView v : m.coins()) {
            CoinDef d = m.coinDefs().stream().filter(x -> x.id().equals(v.id())).findFirst().get();
            double ratio = (double) v.price() / d.startPrice();
            assertTrue(ratio > 0.2 && ratio < 5.0, v.id() + " odpłynął od ceny startowej: x" + ratio);
        }
    }

    @Test
    void defaultEventsCoverEveryCoinWithBoomAndBust() {
        Map<String, int[]> perCoin = new HashMap<>();
        for (SimConfig.EventDef e : SimConfig.defaults().events()) {
            int[] c = perCoin.computeIfAbsent(e.coin(), k -> new int[2]);
            if (e.multiplier() > 1) c[0]++; else if (e.multiplier() < 1) c[1]++;
        }
        for (String coin : List.of("MineCoin", "Enderium", "NetherTether", "CreepDoge", "DiamondHands", "RedstoneChain", "SlimeToken", "WardenCoin")) {
            assertNotNull(perCoin.get(coin), coin);
            assertEquals(1, perCoin.get(coin)[0], coin + ": dokładnie jedna hossa");
            assertEquals(1, perCoin.get(coin)[1], coin + ": dokładnie jedna bessa");
        }
    }
}
