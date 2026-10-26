package pl.kryptogielda.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.kryptogielda.core.Fixed;
import pl.kryptogielda.core.MarketService;
import pl.kryptogielda.core.MarketService.CoinView;
import pl.kryptogielda.core.MarketService.HoldingView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Menu w grze (skrzynie). Cała logika handlu jest w {@link GieldaPlugin}; tu tylko widok i kliknięcia. */
final class Gui implements Listener {

    enum Type { MAIN, PORTFOLIO, MARKET, BROKER }

    /** Sloty kryptowalut w widokach portfela i rynku (do 14 sztuk). */
    private static final int[] COIN_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] AMOUNTS = {1, 10, 100};
    private static final int BUY_BASE = 19, SELL_BASE = 28;

    static final class Holder implements InventoryHolder {
        final Type type;
        final String coin;
        Inventory inv;
        /** Powiązanie slot -> kryptowaluta (dla widoków z listą). */
        final Map<Integer, String> coinAt = new HashMap<>();

        Holder(Type type, String coin) { this.type = type; this.coin = coin; }

        @Override public Inventory getInventory() { return inv; }
    }

    private record Snapshot(List<CoinView> coins, MarketService.PortfolioView portfolio) {}

    private final GieldaPlugin pl;

    Gui(GieldaPlugin pl) { this.pl = pl; }

    // ------------------------------------------------------------------ otwieranie / odświeżanie

    void open(Player p, Type type, String coin) {
        Holder h = new Holder(type, coin);
        final String owner = pl.owner(p);
        pl.io(() -> {
            Snapshot snap = snapshot(owner);
            pl.main(() -> {
                if (!p.isOnline()) return;
                int rows = switch (type) { case MAIN -> 3; case BROKER -> 5; default -> 4; };
                String title = switch (type) {
                    case MAIN -> pl.msg.raw("gui.main.title");
                    case PORTFOLIO -> pl.msg.raw("gui.portfolio.title");
                    case MARKET -> pl.msg.raw("gui.market.title");
                    case BROKER -> pl.msg.format("gui.broker.title", "coin", coin);
                };
                h.inv = Bukkit.createInventory(h, rows * 9, GieldaPlugin.color(title));
                render(h, snap, p);
                p.openInventory(h.inv);
                p.playSound(p.getLocation(), "ui.button.click", 0.6f, 1f);
            });
        });
    }

    private Snapshot snapshot(String owner) {
        return new Snapshot(pl.market.coins(), pl.market.portfolio(owner));
    }

    /** Odświeża otwarte widoki (rynek, broker, portfel) co 2 sekundy. */
    void refreshOpen() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) || h.type == Type.MAIN) continue;
            final String owner = pl.owner(p);
            pl.io(() -> {
                Snapshot snap = snapshot(owner);
                pl.main(() -> {
                    if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == h) render(h, snap, p);
                });
            });
        }
    }

    private void refresh(Player p, Holder h) {
        final String owner = pl.owner(p);
        pl.io(() -> {
            Snapshot snap = snapshot(owner);
            pl.main(() -> { if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == h) render(h, snap, p); });
        });
    }

    // ------------------------------------------------------------------ rysowanie

    private void render(Holder h, Snapshot s, Player p) {
        Inventory inv = h.inv;
        h.coinAt.clear();
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        switch (h.type) {
            case MAIN -> renderMain(inv);
            case PORTFOLIO -> renderPortfolio(h, s);
            case MARKET -> renderMarket(h, s);
            case BROKER -> renderBroker(h, s, p);
        }
    }

    private void renderMain(Inventory inv) {
        inv.setItem(11, item(Material.ENDER_CHEST, pl.msg.raw("gui.main.portfolio"), pl.msg.raw("gui.main.portfolio.lore")));
        inv.setItem(15, item(Material.GOLD_INGOT, pl.msg.raw("gui.main.market"), pl.msg.raw("gui.main.market.lore")));
        if (pl.web != null) inv.setItem(22, item(Material.COMPASS, pl.msg.raw("gui.main.web"), pl.msg.raw("gui.main.web.lore")));
    }

    private void renderPortfolio(Holder h, Snapshot s) {
        Map<String, HoldingView> mine = new HashMap<>();
        for (HoldingView hv : s.portfolio().holdings()) if (hv.qty() > 0) mine.put(hv.coin(), hv);
        int i = 0;
        for (CoinView c : s.coins()) {
            HoldingView hv = mine.get(c.id());
            if (hv == null) continue;
            if (i >= COIN_SLOTS.length) break;
            long diff = hv.value() - hv.cost();
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(pl.msg.format("gui.coin.holding", "qty", Fixed.qty(hv.qty())));
            lore.add(pl.msg.format("gui.coin.value", "value", Fixed.money(hv.value()), "cur", pl.cur()));
            lore.add(pl.msg.format("gui.coin.cost", "cost", Fixed.money(hv.cost()), "cur", pl.cur()));
            lore.add(pl.msg.format("gui.coin.pl", "color", diff >= 0 ? "&a+" : "&c", "diff", Fixed.money(diff), "cur", pl.cur()));
            lore.add("");
            lore.add(pl.msg.raw("gui.coin.click"));
            int slot = COIN_SLOTS[i++];
            h.coinAt.put(slot, c.id());
            h.inv.setItem(slot, item(material(c.icon()), pl.msg.format("gui.coin.name", "coin", c.id()), lore.toArray(new String[0])));
        }
        if (i == 0) h.inv.setItem(13, item(Material.BARRIER, pl.msg.raw("gui.portfolio.empty")));
        h.inv.setItem(31, item(Material.ARROW, pl.msg.raw("gui.back"), pl.msg.raw("gui.back.main")));
    }

    private void renderMarket(Holder h, Snapshot s) {
        int i = 0;
        for (CoinView c : s.coins()) {
            if (i >= COIN_SLOTS.length) break;
            List<String> lore = List.of("",
                    pl.msg.format("gui.coin.trend", "trend", c.trend()), "",
                    pl.msg.format("gui.coin.buy-price", "price", Fixed.money(c.price()), "cur", pl.cur()),
                    pl.msg.format("gui.coin.sell-price", "price", Fixed.money(c.sellPrice()), "cur", pl.cur()), "",
                    pl.msg.raw("gui.coin.click"));
            int slot = COIN_SLOTS[i++];
            h.coinAt.put(slot, c.id());
            h.inv.setItem(slot, item(material(c.icon()), pl.msg.format("gui.coin.name", "coin", c.id()), lore.toArray(new String[0])));
        }
        h.inv.setItem(31, item(Material.ARROW, pl.msg.raw("gui.back"), pl.msg.raw("gui.back.main")));
    }

    private void renderBroker(Holder h, Snapshot s, Player p) {
        CoinView coin = null;
        for (CoinView c : s.coins()) if (c.id().equals(h.coin)) coin = c;
        if (coin == null) { h.inv.setItem(22, item(Material.BARRIER, pl.msg.raw("err.unknown-coin"))); h.inv.setItem(40, item(Material.ARROW, pl.msg.raw("gui.back.market"))); return; }
        HoldingView mine = null;
        for (HoldingView hv : s.portfolio().holdings()) if (hv.coin().equals(h.coin)) mine = hv;

        h.inv.setItem(13, item(material(coin.icon()), pl.msg.format("gui.coin.name", "coin", coin.id()),
                "", pl.msg.format("gui.coin.trend", "trend", coin.trend()),
                pl.msg.format("gui.coin.buy-price", "price", Fixed.money(coin.price()), "cur", pl.cur()),
                pl.msg.format("gui.coin.sell-price", "price", Fixed.money(coin.sellPrice()), "cur", pl.cur()),
                pl.msg.format("gui.broker.fee", "fee", String.format("%.2f", pl.cfg.settings.sellFeeBp() / 100.0))));

        for (int i = 0; i < AMOUNTS.length; i++) {
            h.inv.setItem(BUY_BASE + i, item(Material.LIME_CONCRETE, pl.msg.format("gui.broker.buy", "amount", AMOUNTS[i])));
            h.inv.setItem(SELL_BASE + i, item(Material.RED_CONCRETE, pl.msg.format("gui.broker.sell", "amount", AMOUNTS[i])));
        }
        h.inv.setItem(BUY_BASE + 3, item(Material.EMERALD_BLOCK, pl.msg.raw("gui.broker.buy-max")));
        h.inv.setItem(SELL_BASE + 3, item(Material.REDSTONE_BLOCK, pl.msg.raw("gui.broker.sell-all")));

        long qty = mine == null ? 0 : mine.qty();
        long val = mine == null ? 0 : mine.value();
        h.inv.setItem(24, item(Material.CHEST, pl.msg.format("gui.broker.holding", "qty", Fixed.qty(qty), "value", Fixed.money(val), "cur", pl.cur())));
        var eco = pl.economy();
        if (eco != null) {
            h.inv.setItem(33, item(Material.GOLD_NUGGET, pl.msg.format("gui.broker.wallet", "balance", Fixed.money(GieldaPlugin.toCents(eco.getBalance(p))), "cur", pl.cur())));
        }
        h.inv.setItem(40, item(Material.ARROW, pl.msg.raw("gui.back.market"), pl.msg.raw("gui.back.main")));
    }

    // ------------------------------------------------------------------ kliknięcia

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);   // nic nie wolno wyciągać ani wkładać
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (!p.hasPermission("gielda.use")) return;

        switch (h.type) {
            case MAIN -> {
                if (slot == 11) open(p, Type.PORTFOLIO, null);
                else if (slot == 15) open(p, Type.MARKET, null);
            }
            case PORTFOLIO, MARKET -> {
                if (slot == 31) open(p, Type.MAIN, null);
                else if (h.coinAt.containsKey(slot)) open(p, Type.BROKER, h.coinAt.get(slot));
            }
            case BROKER -> broker(p, h, slot);
        }
    }

    private void broker(Player p, Holder h, int slot) {
        if (slot == 40) { open(p, Type.MARKET, null); return; }
        Runnable done = () -> refresh(p, h);
        for (int i = 0; i < AMOUNTS.length; i++) {
            if (slot == BUY_BASE + i) { pl.gameBuy(p, h.coin, AMOUNTS[i] * 10_000L, done); return; }
            if (slot == SELL_BASE + i) { pl.gameSell(p, h.coin, AMOUNTS[i] * 10_000L, done); return; }
        }
        if (slot == BUY_BASE + 3) pl.gameBuyMax(p, h.coin, done);
        else if (slot == SELL_BASE + 3) pl.gameSellAll(p, h.coin, done);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ przedmioty

    private static Material material(String name) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null || !m.isItem() ? Material.GOLD_NUGGET : m;
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(GieldaPlugin.color(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String x : lore) l.add(GieldaPlugin.color(x).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }
}
