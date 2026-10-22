package pl.kryptogielda.plugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.kryptogielda.core.CoinDef;
import pl.kryptogielda.core.Settings;
import pl.kryptogielda.sim.SimConfig;
import pl.kryptogielda.sim.SimConfig.EventDef;
import pl.kryptogielda.web.WebServer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Wczytuje config.yml i buduje z niego niezmienne obiekty ustawień. Błędne wartości są poprawiane i zgłaszane w logu. */
final class PluginConfig {

    private static final Pattern COIN_ID = Pattern.compile("[A-Za-z0-9_]{2,24}");

    final Settings settings;
    final List<CoinDef> coins;
    final SimConfig sim;
    final WebServer.Config web;

    final boolean chatTrades;
    final boolean chatBots;
    final long chatMinValue;

    final String dbType;
    final String jdbcUrl;
    final String dbUser;
    final String dbPassword;

    final ConfigurationSection importSection;

    private PluginConfig(Settings settings, List<CoinDef> coins, SimConfig sim, WebServer.Config web,
                         boolean chatTrades, boolean chatBots, long chatMinValue,
                         String dbType, String jdbcUrl, String dbUser, String dbPassword, ConfigurationSection importSection) {
        this.settings = settings; this.coins = coins; this.sim = sim; this.web = web;
        this.chatTrades = chatTrades; this.chatBots = chatBots; this.chatMinValue = chatMinValue;
        this.dbType = dbType; this.jdbcUrl = jdbcUrl; this.dbUser = dbUser; this.dbPassword = dbPassword;
        this.importSection = importSection;
    }

    private static long cents(double v) { return Math.round(v * 100.0); }

    static PluginConfig load(FileConfiguration c, File dataFolder, Logger log) {
        Settings d = Settings.defaults();

        // ---- rynek
        ConfigurationSection m = c.getConfigurationSection("market");
        if (m == null) m = c.createSection("market");
        String currency = m.getString("currency", d.currency()).trim();
        if (currency.isEmpty() || currency.length() > 10) currency = d.currency();
        Settings settings = new Settings(
                currency,
                Math.max(1, cents(m.getDouble("min-price", d.minPrice() / 100.0))),
                (int) Math.max(0, Math.min(5000, Math.round(m.getDouble("sell-fee-percent", d.sellFeeBp() / 100.0) * 100))),
                Math.max(100, cents(m.getDouble("depth-value-per-percent", d.impactValueForOnePercent() / 100.0))),
                Math.max(0.01, Math.min(10.0, m.getDouble("max-price-move-per-trade-percent", d.maxBuyMove() * 100) / 100.0)),
                Math.max(1, m.getInt("max-orders-per-player", d.maxOrdersPerPlayer())),
                Math.max(1, cents(m.getDouble("min-trade-value", d.minTradeValue() / 100.0))),
                Math.max(1L, m.getLong("history-days", d.historyRetentionMs() / 86_400_000L)) * 86_400_000L,
                Math.max(0, cents(m.getDouble("bot-start-balance", d.botStartBalance() / 100.0))));

        // ---- kryptowaluty
        List<CoinDef> coins = new ArrayList<>();
        ConfigurationSection cs = c.getConfigurationSection("coins");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection x = cs.getConfigurationSection(id);
                if (x == null) continue;
                if (!COIN_ID.matcher(id).matches()) { log.warning("Pomijam kryptowalutę o niepoprawnej nazwie: '" + id + "' (dozwolone: litery, cyfry, _; 2-24 znaków)."); continue; }
                long price = Math.max(settings.minPrice(), cents(x.getDouble("price", 100)));
                double lo = x.getDouble("volatility-min", 0.95), hi = x.getDouble("volatility-max", 1.05);
                if (lo <= 0 || hi <= 0 || lo > hi) { log.warning("Kryptowaluta " + id + ": niepoprawna zmienność, używam 0.95-1.05."); lo = 0.95; hi = 1.05; }
                coins.add(new CoinDef(id, price, x.getString("icon", "GOLD_NUGGET"), lo, hi, Math.max(0, x.getDouble("liquidity", 0))));
            }
        }
        if (coins.isEmpty()) log.severe("W config.yml nie zdefiniowano żadnej kryptowaluty (sekcja 'coins')!");

        // ---- symulacja
        SimConfig sd = SimConfig.defaults();
        ConfigurationSection s = c.getConfigurationSection("simulation");
        if (s == null) s = c.createSection("simulation");
        ConfigurationSection cyc = sub(s, "market-cycle"), bots = sub(s, "bots"), ev = sub(s, "events");
        ConfigurationSection bBuy = sub(bots, "buy-multiplier"), bSell = sub(bots, "sell-multiplier"), bFrac = sub(bots, "sell-fraction");
        List<String> botNames = bots.isList("names") ? bots.getStringList("names") : sd.bots();
        List<EventDef> events = new ArrayList<>();
        if (ev.isList("list")) {
            for (Map<?, ?> e : ev.getMapList("list")) {
                String text = str(e.get("text"), "");
                if (text.isBlank()) continue;
                events.add(new EventDef(str(e.get("type"), "WIADOMOŚĆ"), text, str(e.get("coin"), ""),
                        num(e.get("multiplier"), 1.0), (int) Math.max(0, Math.round(num(e.get("weight"), 1))), str(e.get("color"), "")));
            }
        } else events = SimConfig.defaultEvents();
        SimConfig sim = new SimConfig(
                Math.max(1, s.getInt("orders-interval-seconds", sd.ordersIntervalSec())),
                bots.getBoolean("enabled", sd.botsEnabled()),
                Math.max(1, bots.getInt("interval-seconds", sd.botIntervalSec())),
                clampPct(bots.getInt("action-chance-percent", sd.botChancePct())),
                botNames,
                Math.max(100, cents(bots.getDouble("budget-min", sd.botBudgetMin() / 100.0))),
                Math.max(100, cents(bots.getDouble("budget-max", sd.botBudgetMax() / 100.0))),
                clampPct(bots.getInt("buy-chance-percent", sd.botBuyChancePct())),
                bBuy.getDouble("min", sd.botBuyMultMin()), bBuy.getDouble("max", sd.botBuyMultMax()),
                bSell.getDouble("min", sd.botSellMultMin()), bSell.getDouble("max", sd.botSellMultMax()),
                bFrac.getDouble("min", sd.botSellFractionMin()), bFrac.getDouble("max", sd.botSellFractionMax()),
                cyc.getBoolean("enabled", sd.simEnabled()),
                Math.max(5, cyc.getInt("interval-seconds", sd.simIntervalSec())),
                Math.max(0, cyc.getDouble("mood-max-percent", sd.marketMoodMax() * 100) / 100.0),
                Math.max(0, Math.min(1, cyc.getDouble("mean-reversion", sd.meanReversion()))),
                ev.getBoolean("enabled", sd.eventsEnabled()),
                Math.max(10, ev.getInt("interval-seconds", sd.eventIntervalSec())),
                clampPct(ev.getInt("chance-percent", sd.eventChancePct())),
                events,
                s.getBoolean("micro-ticks.enabled", sd.microEnabled()),
                Math.max(1, s.getInt("micro-ticks.interval-seconds", sd.microIntervalSec())),
                Math.max(0, Math.min(5, s.getDouble("micro-ticks.max-percent", sd.microMaxPercent()))));

        // ---- WWW
        WebServer.Config wd = WebServer.Config.defaults();
        ConfigurationSection w = sub(c, "web");
        Path override = null;
        String ovr = w.getString("override-dir", "web");
        if (ovr != null && !ovr.isBlank()) {
            Path p = dataFolder.toPath().resolve(ovr).normalize();
            if (p.startsWith(dataFolder.toPath()) && Files.isDirectory(p)) override = p;
        }
        WebServer.Config web = new WebServer.Config(
                w.getBoolean("enabled", wd.enabled()),
                w.getString("bind", wd.bind()),
                Math.max(0, Math.min(65535, w.getInt("port", wd.port()))),
                w.getString("public-url", "").trim(),
                w.getBoolean("secure-cookie", wd.secureCookie()),
                w.getBoolean("trust-forwarded-headers", wd.trustForwarded()),
                Math.max(1, w.getInt("session-hours", wd.sessionHours())),
                Math.max(1, w.getInt("link-code-minutes", wd.linkCodeMinutes())),
                w.getBoolean("ranking-show-bots", wd.rankingBots()),
                Math.max(5, Math.min(200, w.getInt("ranking-size", wd.rankingSize()))),
                override,
                w.getStringList("allowed-origins"),
                w.getString("title", wd.title()),
                w.getBoolean("label-bots", wd.labelBots()),
                Math.max(1, Math.min(1000, w.getInt("rate-limit-multiplier", wd.rateScale()))));

        // ---- czat
        ConfigurationSection ch = sub(c, "chat");

        // ---- baza
        ConfigurationSection db = sub(c, "database");
        String type = db.getString("type", "sqlite").trim().toLowerCase();
        String url, user = null, pass = null;
        if (type.equals("mysql")) {
            ConfigurationSection my = sub(db, "mysql");
            String params = my.getString("params", "useSSL=false&characterEncoding=utf8&serverTimezone=UTC");
            url = "jdbc:mysql://" + my.getString("host", "127.0.0.1") + ":" + my.getInt("port", 3306) + "/" + my.getString("database", "gielda")
                    + (params.isBlank() ? "" : "?" + params);
            user = my.getString("user", "gielda");
            pass = my.getString("password", "");
        } else {
            type = "sqlite";
            String file = db.getString("sqlite-file", "gielda.db");
            if (file.contains("..") || file.contains("/") || file.contains("\\")) file = "gielda.db";
            url = "jdbc:sqlite:" + new File(dataFolder, file).getAbsolutePath();
        }

        return new PluginConfig(settings, coins, sim, web,
                ch.getBoolean("broadcast-trades", true), ch.getBoolean("broadcast-bots", true),
                Math.max(0, cents(ch.getDouble("min-broadcast-value", 0))),
                type, url, user, pass, c.getConfigurationSection("import"));
    }

    private static ConfigurationSection sub(ConfigurationSection p, String name) {
        ConfigurationSection s = p.getConfigurationSection(name);
        return s != null ? s : p.createSection(name);
    }

    private static int clampPct(int v) { return Math.max(0, Math.min(100, v)); }

    private static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return def;
        try { return Double.parseDouble(String.valueOf(o).replace(',', '.')); } catch (NumberFormatException e) { return def; }
    }

    /** Nazwy kryptowalut jako mapa id -> definicja (do podpowiedzi). */
    Map<String, CoinDef> coinMap() {
        Map<String, CoinDef> r = new LinkedHashMap<>();
        for (CoinDef d : coins) r.put(d.id(), d);
        return r;
    }
}
