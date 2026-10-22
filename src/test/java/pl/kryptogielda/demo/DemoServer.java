package pl.kryptogielda.demo;

import pl.kryptogielda.core.*;
import pl.kryptogielda.sim.SimConfig;
import pl.kryptogielda.store.JdbcStore;
import pl.kryptogielda.web.WebServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Ręczne uruchomienie strony z wygenerowaną historią (do oglądania w przeglądarce). Nie jest częścią pluginu. */
public class DemoServer {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("kgdemo");
        JdbcStore store = new JdbcStore("jdbc:sqlite:" + dir.resolve("d.db"), null, null);
        long start = System.currentTimeMillis() - 8 * 3600_000L;
        AtomicLong clock = new AtomicLong(start);
        MarketService m = new MarketService(store, Settings.defaults(), clock::get, Logger.getLogger("demo"));
        m.init(List.of(new CoinDef("MineCoin", 10_000, "GOLD_NUGGET", 0.96, 1.04),
                new CoinDef("CreepDoge", 2_500, "GUNPOWDER", 0.85, 1.15),
                new CoinDef("EnderCash", 5_000, "ENDER_PEARL", 0.90, 1.10)));
        Random r = new Random(7);
        m.ensureAccount("u-steve", "Steve");
        m.deposit("u-steve", "Steve", 500_000);
        for (long t = start; t < System.currentTimeMillis(); t += 30_000) {
            clock.set(t);
            for (String c : List.of("MineCoin", "CreepDoge", "EnderCash")) m.applyMultiplier(c, 1 + (r.nextDouble() - 0.5) * 0.02, false);
        }
        clock.set(System.currentTimeMillis());
        m.addNews("green", "Wydarzenie: wielka kopalnia odkryta – MineCoin rośnie!");
        m.addNews("red", "Wydarzenie: creeper w banku – CreepDoge traci.");
        m.buyWithBalance("u-steve", "Steve", "MineCoin", 100_000, "WEB");
        m.buyWithBalance("u-steve", "Steve", "CreepDoge", 500_000, "WEB");
        m.placeLimit("u-steve", "Steve", Side.BUY, "EnderCash", 20_000, 4_000);
        m.ensureAccount("bot-1", "TradeBot"); m.deposit("bot-1", "TradeBot", 1_000_000);
        m.botTrade("TradeBot", "MineCoin", Side.BUY, 100_000, 0.5, 1.01);

        WebServer.GameBridge b = new WebServer.GameBridge() {
            public void kickPayouts() {}
            public void forceMarketUpdate() { m.applyMultiplier("MineCoin", 1.03, false); }
            public List<SimConfig.EventDef> events() { return SimConfig.defaults().events(); }
            public void runEvent(int i) {}
            public void announceReset() {}
            public void auditLog(String n, String x) { System.out.println("AUDIT " + n + ": " + x); }
        };
        WebServer.Config c = new WebServer.Config(true, "127.0.0.1", 18123, "", false, false, 12, 60, true, 50, null, List.of(), "Giełda Krypto", true, 1);
        WebServer w = new WebServer(c, m, b, Logger.getLogger("demo"), System::currentTimeMillis);
        w.start();
        System.out.println("READY http://127.0.0.1:18123");
        System.out.println("CODE " + w.linkCodes().create("u-steve", "Steve", true, System.currentTimeMillis()));
        Thread.sleep(Long.MAX_VALUE);
    }
}
