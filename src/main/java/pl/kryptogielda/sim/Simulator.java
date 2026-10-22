package pl.kryptogielda.sim;

import pl.kryptogielda.core.*;
import pl.kryptogielda.core.Model.Fill;
import pl.kryptogielda.sim.SimConfig.EventDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Silnik rynku działający w tle: realizacja zleceń limit, boty, cykl cen i eventy.
 * Wywołuj {@link #tick(long)} co kilka sekund (czas przekazywany z zewnątrz - łatwe testowanie).
 */
public final class Simulator {

    public interface Notifier {
        /** Wysyła wiadomość do wszystkich graczy (kody kolorów &amp;). */
        void broadcast(String message);
    }

    private final MarketService market;
    private final SimConfig cfg;
    private final Notifier notifier;
    private final Messages msg;
    private final Random rnd;
    private final Logger log;

    private long nextOrders = -1, nextBot, nextSim, nextEvent, nextMicro;

    public Simulator(MarketService market, SimConfig cfg, Notifier notifier, Messages msg, Random rnd, Logger log) {
        this.market = market;
        this.cfg = cfg;
        this.notifier = notifier;
        this.msg = msg;
        this.rnd = rnd;
        this.log = log;
    }

    public synchronized void tick(long now) {
        if (nextOrders < 0) {
            nextOrders = now + cfg.ordersIntervalSec() * 1000L;
            nextBot = now + cfg.botIntervalSec() * 1000L;
            nextSim = now + cfg.simIntervalSec() * 1000L;
            nextEvent = now + cfg.eventIntervalSec() * 1000L;
            nextMicro = now + cfg.microIntervalSec() * 1000L;
        }
        if (now >= nextOrders) {
            nextOrders = now + cfg.ordersIntervalSec() * 1000L;
            safely("zlecenia limit", () -> market.processOrders());
        }
        if (cfg.microEnabled() && now >= nextMicro) {
            nextMicro = now + Math.max(1, cfg.microIntervalSec()) * 1000L;
            safely("mikro-ruchy", this::microTick);
        }
        if (cfg.botsEnabled() && now >= nextBot) {
            nextBot = now + cfg.botIntervalSec() * 1000L;
            if (rnd.nextInt(100) < cfg.botChancePct()) safely("bot", this::botAction);
        }
        if (cfg.simEnabled() && now >= nextSim) {
            nextSim = now + cfg.simIntervalSec() * 1000L;
            safely("symulacja", this::marketUpdate);
        }
        if (cfg.eventsEnabled() && now >= nextEvent) {
            nextEvent = now + cfg.eventIntervalSec() * 1000L;
            if (rnd.nextInt(100) < cfg.eventChancePct()) safely("event", () -> randomEvent());
        }
    }

    private void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.log(Level.WARNING, "Błąd silnika rynku (" + what + ")", t);
        }
    }

    private double uniform(double a, double b) { return a + rnd.nextDouble() * (b - a); }

    private long uniform(long a, long b) { return a >= b ? a : a + (long) (rnd.nextDouble() * (b - a)); }

    // ------------------------------------------------------------------ boty

    public void botAction() {
        List<CoinDef> coins = market.coinDefs();
        if (cfg.bots().isEmpty() || coins.isEmpty()) return;
        String bot = cfg.bots().get(rnd.nextInt(cfg.bots().size()));
        CoinDef coin = coins.get(rnd.nextInt(coins.size()));
        long held = market.holdingQty(MarketService.botOwner(bot), coin.id());
        boolean buy = held <= 0 || rnd.nextInt(100) < cfg.botBuyChancePct();
        if (buy) {
            market.botTrade(bot, coin.id(), Side.BUY, uniform(cfg.botBudgetMin(), cfg.botBudgetMax()), 0,
                    uniform(cfg.botBuyMultMin(), cfg.botBuyMultMax()));
        } else {
            market.botTrade(bot, coin.id(), Side.SELL, 0, uniform(cfg.botSellFractionMin(), cfg.botSellFractionMax()),
                    uniform(cfg.botSellMultMin(), cfg.botSellMultMax()));
        }
    }

    // ------------------------------------------------------------------ mikro-ruchy (żywy rynek)

    /**
     * Drobne, losowe ruchy cen co kilka sekund. To prawdziwe zmiany ceny (po takiej cenie handlują gracze i boty),
     * dzięki którym wykres „żyje” między głównymi cyklami rynku. Skala ruchu zależy od zmienności waluty.
     */
    public void microTick() {
        double base = cfg.microMaxPercent() / 100.0;
        if (base <= 0) return;
        for (CoinDef d : market.coinDefs()) {
            double factor = Math.max(0.2, Math.min(3.0, (d.volMax() - d.volMin()) / 0.08));
            double mult = 1.0 + (rnd.nextDouble() * 2 - 1) * base * factor;
            market.applyMultiplier(d.id(), mult, false);
        }
    }

    // ------------------------------------------------------------------ cykl cen

    /** Standardowa aktualizacja rynku (co kilka minut). Wywoływana też ręcznie przez admina. */
    public void marketUpdate() {
        double mood = (rnd.nextInt(2001) - 1000) / 1000.0 * cfg.marketMoodMax();
        String cur = market.settings().currency();
        String trend = msg.raw("trend.flat");
        if (mood > 0.01) trend = msg.raw("trend.up");
        else if (mood < -0.01) trend = msg.raw("trend.down");

        List<String> lines = new ArrayList<>();
        lines.add(msg.raw("update.title"));
        lines.add(msg.format("update.trend", "trend", trend));
        for (CoinDef d : market.coinDefs()) {
            double mult = uniform(d.volMin(), d.volMax()) + mood;
            long price = market.price(d.id());
            if (price > 0 && cfg.meanReversion() > 0) {
                double pull = Math.pow((double) d.startPrice() / price, cfg.meanReversion());
                mult *= Math.max(0.9, Math.min(1.1, pull));
            }
            Result<MarketService.PriceChange> r = market.applyMultiplier(d.id(), mult, true);
            if (!r.ok) continue;
            long diff = r.value.newPrice() - r.value.oldPrice();
            String change = diff > 0 ? msg.format("change.up", "diff", Fixed.money(diff), "cur", cur)
                    : diff < 0 ? msg.format("change.down", "diff", Fixed.money(diff), "cur", cur)
                    : msg.format("change.flat", "cur", cur);
            lines.add(msg.format("update.coin", "coin", d.id(), "price", Fixed.money(r.value.newPrice()), "cur", cur, "change", change));
        }
        for (String l : lines) notifier.broadcast(l);
    }

    // ------------------------------------------------------------------ eventy

    /** Losuje event wg wag i wykonuje go. */
    public EventDef randomEvent() {
        List<EventDef> list = cfg.events();
        int total = 0;
        for (EventDef e : list) total += Math.max(0, e.weight());
        if (total <= 0) return null;
        int pick = rnd.nextInt(total);
        for (EventDef e : list) {
            pick -= Math.max(0, e.weight());
            if (pick < 0) { runEvent(e); return e; }
        }
        return null;
    }

    public void runEvent(EventDef e) {
        market.addNews(e.type(), e.text());
        if (e.coin() != null && !e.coin().isBlank() && e.multiplier() > 0 && market.canon(e.coin()) != null) {
            market.applyMultiplier(e.coin(), e.multiplier(), false);
        }
        String color = e.color() != null && !e.color().isBlank() ? e.color() : (e.multiplier() >= 1 ? "&a" : "&c");
        notifier.broadcast(msg.format("event.line", "type", e.type(), "color", color, "text", e.text()));
    }

    public List<EventDef> events() { return cfg.events(); }
}
