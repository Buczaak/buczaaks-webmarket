package pl.kryptogielda.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import pl.kryptogielda.core.*;
import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.sim.SimConfig;
import pl.kryptogielda.sim.Simulator;
import pl.kryptogielda.store.JdbcStore;
import pl.kryptogielda.web.WebServer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Główna klasa pluginu: spina rynek, symulator, stronę WWW, Vault i komendy. */
public final class GieldaPlugin extends JavaPlugin implements MarketService.Listener, Simulator.Notifier, WebServer.GameBridge {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    PluginConfig cfg;
    Messages msg = new Messages();
    JdbcStore store;
    MarketService market;
    Simulator sim;
    WebServer web;
    Gui gui;

    private ExecutorService io;
    private ScheduledExecutorService sched;
    private volatile Economy economy;
    private volatile boolean stopping;
    private int tickCount;

    // ------------------------------------------------------------------ cykl życia

    @Override
    public void onEnable() {
        // pre-1.0.2 installs used plugins/KryptoGielda/ - move their files to plugins/WebMarket/
        DataFolderMigration.migrate(getDataFolder().toPath().resolveSibling(DataFolderMigration.OLD_NAME), getDataFolder().toPath(), getLogger());
        saveDefaultConfig();
        reloadConfig();
        cfg = PluginConfig.load(getConfig(), getDataFolder(), getLogger());
        loadMessages();

        try {
            store = new JdbcStore(cfg.jdbcUrl, cfg.dbUser, cfg.dbPassword);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Cannot open the database (" + cfg.dbType + "): " + e.getMessage() + ". Disabling the plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        market = new MarketService(store, cfg.settings, System::currentTimeMillis, getLogger());
        market.init(cfg.coins);
        market.addListener(this);
        sim = new Simulator(market, cfg.sim, this, msg, new Random(), getLogger());

        io = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "WebMarket-IO"); t.setDaemon(true); return t; });
        sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "WebMarket-Tick"); t.setDaemon(true); return t; });

        if (cfg.web.enabled()) {
            try {
                web = new WebServer(cfg.web, market, this, getLogger(), System::currentTimeMillis);
                web.start();
                getLogger().info("The website is running on port " + web.port() + " (bind address: " + cfg.web.bind() + ").");
            } catch (IOException | RuntimeException e) {
                getLogger().log(Level.SEVERE, "Could not start the website (port " + cfg.web.port() + "): " + e.getMessage());
                web = null;
            }
        }

        Commands commands = new Commands(this);
        for (String name : List.of("gielda", "wplac", "wyplac", "gieldaadmin")) {
            var cmd = getCommand(name);
            if (cmd != null) { cmd.setExecutor(commands); cmd.setTabCompleter(commands); }
        }
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        Bukkit.getScheduler().runTaskTimer(this, gui::refreshOpen, 40L, 40L);

        sched.scheduleWithFixedDelay(this::tick, 5, 1, TimeUnit.SECONDS);
        Bukkit.getScheduler().runTaskLater(this, this::checkStartup, 20L);
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (sched != null) sched.shutdownNow();
        if (io != null) {
            io.shutdown();
            try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (web != null) web.stop();
        if (store != null) store.close();
    }

    private void checkStartup() {
        if (economy() == null) {
            getLogger().warning("No Vault economy found (e.g. EssentialsX). In-game trading, /wplac and /wyplac will not work.");
        }
        io.execute(() -> {
            int stuck = market.payouts(MarketService.PAYOUT_SENDING).size();
            if (stuck > 0) getLogger().warning(stuck + " payout(s) stuck in the SENDING state (interrupted by a restart). Check them: /gieldaadmin wyplaty");
        });
    }

    void loadMessages() {
        // pl: messages.yml (jak dotąd), en: messages_en.yml - każdy język ma własny plik do ręcznej edycji
        final Map<String, String> defaults = Messages.defaultsFor(cfg.language);
        File f = new File(getDataFolder(), "en".equals(cfg.language) ? "messages_en.yml" : "messages.yml");
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try {
            if (f.exists()) y.load(f);
        } catch (Exception e) {
            getLogger().warning("Error in " + f.getName() + " (" + e.getMessage() + ") - using default messages.");
            y = new YamlConfiguration();
            y.options().pathSeparator('/');
        }
        boolean changed = !f.exists();
        Messages m = Messages.forLanguage(cfg.language);
        for (Map.Entry<String, String> e : new TreeMap<>(defaults).entrySet()) {
            if (y.isString(e.getKey())) m.put(e.getKey(), y.getString(e.getKey()));
            else { y.set(e.getKey(), e.getValue()); changed = true; }
        }
        if (changed) {
            try { getDataFolder().mkdirs(); y.save(f); } catch (IOException e) { getLogger().warning("Cannot save " + f.getName() + ": " + e.getMessage()); }
        }
        this.msg = m;
    }

    // ------------------------------------------------------------------ pomocnicze

    static Component color(String s) { return LEGACY.deserialize(s); }

    String cur() { return cfg.settings.currency(); }

    void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(color(msg.raw("prefix") + msg.format(key, kv)));
    }

    void sendRaw(CommandSender to, String key, Object... kv) {
        to.sendMessage(color(msg.format(key, kv)));
    }

    void io(Runnable r) {
        if (stopping || io == null) return;
        try {
            io.execute(() -> {
                try { r.run(); } catch (Throwable t) { getLogger().log(Level.WARNING, "Error in an exchange task", t); }
            });
        } catch (RejectedExecutionException ignored) { /* zamykanie */ }
    }

    void main(Runnable r) {
        if (!isEnabled()) return;
        try { Bukkit.getScheduler().runTask(this, r); } catch (IllegalStateException | org.bukkit.plugin.IllegalPluginAccessException ignored) { /* zamykanie */ }
    }

    Economy economy() {
        Economy e = economy;
        if (e == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = e = rsp.getProvider();
        }
        return e;
    }

    static double toMoney(long cents) { return cents / 100.0; }

    static long toCents(double money) { return Math.round(money * 100.0); }

    String owner(Player p) { return p.getUniqueId().toString(); }

    // ------------------------------------------------------------------ cykliczne zadania

    private void tick() {
        try {
            sim.tick(System.currentTimeMillis());
            if (++tickCount % 5 == 0) processPayouts();
            if (tickCount == 120 || tickCount % 21_600 == 0) market.prune();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Error in the exchange cycle", t);
        }
    }

    /** Wykonuje oczekujące wypłaty z giełdy do salda Vault. Wywoływane poza głównym wątkiem. */
    void processPayouts() {
        if (stopping || economy() == null) return;
        for (Payout p : market.payouts(MarketService.PAYOUT_PENDING)) {
            if (!market.markPayoutSending(p.id())) continue;   // ktoś inny już ją przejął
            final Payout payout = p;
            main(() -> {
                boolean ok = false;
                UUID uid = null;
                try {
                    uid = UUID.fromString(payout.owner());
                    EconomyResponse r = economy().depositPlayer(Bukkit.getOfflinePlayer(uid), toMoney(payout.amount()));
                    ok = r.transactionSuccess();
                    if (!ok) getLogger().warning("Vault rejected payout #" + payout.id() + " for " + payout.name() + ": " + r.errorMessage);
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Payout error #" + payout.id(), t);
                }
                final boolean success = ok;
                io(() -> { if (success) market.completePayout(payout.id()); else market.failPayout(payout.id()); });
                Player online = uid == null ? null : Bukkit.getPlayer(uid);
                if (online != null) {
                    if (success) send(online, "withdraw.ok", "value", Fixed.money(payout.amount()), "cur", cur());
                    else send(online, "withdraw.failed");
                }
            });
        }
    }

    // ------------------------------------------------------------------ operacje graczy (komendy i GUI)

    private void fail(Player p, Result<?> r) {
        switch (r.code) {
            case "PRICE_MOVED" -> send(p, "err.price-moved");
            case "NO_COIN" -> send(p, "err.unknown-coin");
            case "INSUFFICIENT_HOLDINGS" -> send(p, "err.not-enough-assets");
            default -> send(p, "err.generic", "message", r.message);
        }
        p.playSound(p.getLocation(), "entity.villager.no", 0.6f, 1f);
    }

    /** Kupno za pieniądze z gry (Vault): najpierw pobranie środków, potem realizacja, na końcu zwrot różnicy. */
    void gameBuy(Player p, String coin, long qty, Runnable done) {
        if (economy() == null) { send(p, "err.no-economy"); return; }
        final String owner = owner(p), name = p.getName();
        io(() -> {
            Result<MarketService.Quote> q = market.quoteBuy(coin, qty);
            if (!q.ok) { main(() -> { fail(p, q); if (done != null) done.run(); }); return; }
            final long cost = q.value.value();
            main(() -> {
                Economy eco = economy();
                if (eco == null) { send(p, "err.no-economy"); return; }
                if (!eco.has(p, toMoney(cost))) {
                    send(p, "err.not-enough-money", "value", Fixed.money(cost), "cur", cur());
                    if (done != null) done.run();
                    return;
                }
                EconomyResponse w = eco.withdrawPlayer(p, toMoney(cost));
                if (!w.transactionSuccess()) { send(p, "err.not-enough-money", "value", Fixed.money(cost), "cur", cur()); if (done != null) done.run(); return; }
                getLogger().info("[trade] " + name + " paid " + Fixed.money(cost) + " for " + Fixed.qty(qty) + " " + coin);
                io(() -> {
                    Result<Fill> r = null;
                    try {
                        r = market.buyPaidExternally(owner, name, coin, qty, cost, MarketService.SRC_GAME);
                    } catch (Throwable t) {
                        getLogger().log(Level.SEVERE, "Purchase error - refunding player " + name, t);
                    }
                    final Result<Fill> res = r;
                    main(() -> {
                        if (res == null || !res.ok) {
                            economy().depositPlayer(p, toMoney(cost));
                            if (res == null) send(p, "err.internal"); else fail(p, res);
                        } else {
                            long diff = cost - res.value.value();
                            if (diff > 0) {
                                economy().depositPlayer(p, toMoney(diff));
                            }
                            send(p, "ok.bought", "qty", Fixed.qty(res.value.qty()), "coin", res.value.coin(), "value", Fixed.money(res.value.value()), "cur", cur());
                            p.playSound(p.getLocation(), "entity.experience_orb.pickup", 0.7f, 1.2f);
                        }
                        if (done != null) done.run();
                    });
                });
            });
        });
    }

    /** Sprzedaż: pozycje znikają z portfela w transakcji, a przychód trafia do salda Vault. */
    void gameSell(Player p, String coin, long qty, Runnable done) {
        if (economy() == null) { send(p, "err.no-economy"); return; }
        final String owner = owner(p), name = p.getName();
        io(() -> {
            Result<Fill> r = market.sellForPayout(owner, name, coin, qty, MarketService.SRC_GAME);
            main(() -> {
                if (!r.ok) { fail(p, r); if (done != null) done.run(); return; }
                final long payout = r.value.value();
                boolean paid = false;
                try {
                    paid = economy() != null && economy().depositPlayer(p, toMoney(payout)).transactionSuccess();
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Payout error after a sale for " + name, t);
                }
                if (paid) {
                    send(p, "ok.sold", "qty", Fixed.qty(r.value.qty()), "coin", r.value.coin(), "value", Fixed.money(payout), "cur", cur());
                    p.playSound(p.getLocation(), "entity.experience_orb.pickup", 0.7f, 1.4f);
                } else {
                    // nie tracimy pieniędzy gracza: kwota ląduje na koncie giełdowym
                    io(() -> market.deposit(owner, name, payout));
                    send(p, "ok.sold-refunded", "value", Fixed.money(payout), "cur", cur());
                }
                if (done != null) done.run();
            });
        });
    }

    void gameBuyMax(Player p, String coin, Runnable done) {
        Economy eco = economy();
        if (eco == null) { send(p, "err.no-economy"); return; }
        final long budget = toCents(eco.getBalance(p));
        io(() -> {
            long qty = market.maxBuyQty(coin, budget);
            main(() -> {
                if (qty <= 0) { send(p, "err.cant-afford-one"); if (done != null) done.run(); return; }
                gameBuy(p, coin, qty, done);
            });
        });
    }

    void gameSellAll(Player p, String coin, Runnable done) {
        final String owner = owner(p);
        io(() -> {
            long qty = market.holdingQty(owner, coin);
            main(() -> {
                if (qty <= 0) { send(p, "err.not-enough-assets"); if (done != null) done.run(); return; }
                gameSell(p, coin, qty, done);
            });
        });
    }

    /** /wplac: pieniądze z gry -> konto giełdowe. */
    void deposit(Player p, long cents) {
        Economy eco = economy();
        if (eco == null) { send(p, "err.no-economy"); return; }
        if (!eco.has(p, toMoney(cents))) { send(p, "deposit.not-enough"); return; }
        if (!eco.withdrawPlayer(p, toMoney(cents)).transactionSuccess()) { send(p, "deposit.not-enough"); return; }
        final String owner = owner(p), name = p.getName();
        io(() -> {
            Result<Long> r = null;
            try { r = market.deposit(owner, name, cents); } catch (Throwable t) { getLogger().log(Level.SEVERE, "Deposit error - refunding " + name, t); }
            final Result<Long> res = r;
            main(() -> {
                if (res == null || !res.ok) {
                    economy().depositPlayer(p, toMoney(cents));
                    send(p, "err.internal");
                } else {
                    send(p, "deposit.ok", "value", Fixed.money(cents), "balance", Fixed.money(res.value), "cur", cur());
                }
            });
        });
    }

    /** /wyplac: konto giełdowe -> kolejka wypłat -> Vault. */
    void withdraw(Player p, long cents) {
        final String owner = owner(p);
        io(() -> {
            Result<Payout> r = market.requestWithdraw(owner, cents);
            main(() -> {
                if (!r.ok) { send(p, "err.generic", "message", r.message); return; }
                send(p, "withdraw.queued", "value", Fixed.money(cents), "cur", cur());
            });
            if (r.ok) processPayouts();
        });
    }

    /** Wywołanie z komend: asynchroniczny odczyt, wynik na głównym wątku. */
    <T> void async(java.util.function.Supplier<T> work, Consumer<T> then) {
        io(() -> {
            T v = work.get();
            main(() -> then.accept(v));
        });
    }

    // ------------------------------------------------------------------ MarketService.Listener (czat)

    @Override
    public void onTrade(Fill f, String source) {
        if (!cfg.chatTrades || f.value() < cfg.chatMinValue) return;
        boolean bot = MarketService.SRC_BOT.equals(source);
        if (bot && !cfg.chatBots) return;
        String kind = bot ? "chat.bot." : MarketService.SRC_LIMIT.equals(source) ? "chat.limit." : "chat.trade.";
        broadcast(msg.format(kind + (f.side() == Side.BUY ? "buy" : "sell"),
                "player", f.name(), "qty", Fixed.qty(f.qty()), "coin", f.coin(), "value", Fixed.money(f.value()), "cur", cur()));
    }

    // ------------------------------------------------------------------ Simulator.Notifier

    @Override
    public void broadcast(String message) {
        Component c = color(message);
        main(() -> Bukkit.getServer().broadcast(c));
    }

    // ------------------------------------------------------------------ WebServer.GameBridge

    @Override public void kickPayouts() { io(this::processPayouts); }

    @Override public void forceMarketUpdate() { io(() -> sim.marketUpdate()); }

    @Override public List<SimConfig.EventDef> events() { return sim.events(); }

    @Override public int configEventCount() { return sim.configEventCount(); }

    @Override public int importDefaultEvents() { return sim.importDefaultEvents(cfg.eventsLanguage); }

    @Override public String language() { return cfg.language; }

    @SuppressWarnings("deprecation")
    @Override public String version() { return getDescription().getVersion(); }

    /** Tekst zależny od języka z config.yml (language: pl / en). */
    String t(String pl, String en) { return "en".equals(cfg.language) ? en : pl; }

    @Override public void runEvent(int index) {
        List<SimConfig.EventDef> l = sim.events();
        if (index >= 0 && index < l.size()) io(() -> sim.runEvent(l.get(index)));
    }

    @Override public void announceReset() { broadcast(msg.raw("reset.broadcast")); }

    @Override public void auditLog(String adminName, String action) {
        getLogger().info("[AUDYT] " + adminName + ": " + action);
    }
}
