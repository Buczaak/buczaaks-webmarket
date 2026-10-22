package pl.kryptogielda.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.kryptogielda.core.*;
import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.sim.SimConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Obsługa komend: /gielda, /wplac, /wyplac, /gieldaadmin. */
final class Commands implements CommandExecutor, TabCompleter {

    private final GieldaPlugin pl;

    Commands(GieldaPlugin pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        try {
            switch (cmd.getName().toLowerCase(Locale.ROOT)) {
                case "gielda" -> gielda(s, a);
                case "wplac" -> money(s, a, true);
                case "wyplac" -> money(s, a, false);
                case "gieldaadmin" -> admin(s, a);
                default -> { return false; }
            }
        } catch (RuntimeException e) {
            pl.getLogger().log(java.util.logging.Level.WARNING, "Błąd komendy", e);
            pl.send(s, "err.internal");
        }
        return true;
    }

    // ------------------------------------------------------------------ gracz

    private void gielda(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help") || sub.equals("pomoc")) {
            pl.sendRaw(s, "help.header");
            for (String k : List.of("menu", "link", "wallet", "market", "balance", "deposit", "withdraw")) pl.sendRaw(s, "help." + k);
            return;
        }
        if (!(s instanceof Player p)) { pl.send(s, "err.player-only"); return; }
        if (!p.hasPermission("gielda.use")) { pl.send(s, "err.no-permission"); return; }
        switch (sub) {
            case "menu" -> pl.gui.open(p, Gui.Type.MAIN, null);
            case "portfel", "portfolio" -> pl.gui.open(p, Gui.Type.PORTFOLIO, null);
            case "rynek", "market" -> pl.gui.open(p, Gui.Type.MARKET, null);
            case "link", "kod" -> link(p);
            case "saldo", "konto" -> {
                final String owner = pl.owner(p);
                pl.async(() -> {
                    MarketService.PortfolioView v = pl.market.portfolio(owner);
                    return v;
                }, v -> pl.send(p, "balance.line", "balance", Fixed.money(v.balance()), "worth", Fixed.money(v.netWorth()), "cur", pl.cur()));
            }
            default -> pl.gui.open(p, Gui.Type.MAIN, null);
        }
    }

    private void link(Player p) {
        if (pl.web == null) { pl.send(p, "link.disabled"); return; }
        String code = pl.web.linkCodes().create(pl.owner(p), p.getName(), p.hasPermission("gielda.admin"), System.currentTimeMillis());
        pl.send(p, "link.code", "code", code, "minutes", pl.cfg.web.linkCodeMinutes());
        String url = pl.cfg.web.publicUrl();
        if (url.isEmpty()) url = "http://<adres-serwera>:" + pl.web.port();
        pl.send(p, "link.url", "url", url);
    }

    private void money(CommandSender s, String[] a, boolean deposit) {
        if (!(s instanceof Player p)) { pl.send(s, "err.player-only"); return; }
        if (!p.hasPermission("gielda.use")) { pl.send(s, "err.no-permission"); return; }
        if (a.length != 1) { pl.send(s, deposit ? "deposit.usage" : "withdraw.usage"); return; }
        long cents = Fixed.parseMoney(a[0].replace(',', '.'));
        if (cents == Fixed.INVALID || cents <= 0) { pl.send(s, "err.invalid-number"); return; }
        if (deposit) pl.deposit(p, cents); else pl.withdraw(p, cents);
    }

    // ------------------------------------------------------------------ admin

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("gielda.admin")) { pl.send(s, "err.no-permission"); return; }
        if (a.length == 0) { usage(s, "/gieldaadmin <cena|pump|dump|trend|wymus|event|reset|gracz|wyplaty|import|reload>"); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String who = s.getName();
        switch (sub) {
            case "cena" -> {
                if (a.length != 3) { usage(s, "/gieldaadmin cena <kryptowaluta> <kwota>"); return; }
                long c = Fixed.parseMoney(a[2].replace(',', '.'));
                if (c == Fixed.INVALID) { pl.send(s, "err.invalid-number"); return; }
                run(s, () -> pl.market.setPrice(a[1], c), "cena " + a[1] + " = " + a[2]);
            }
            case "pump", "dump" -> {
                if (a.length != 3) { usage(s, "/gieldaadmin " + sub + " <kryptowaluta> <procent>"); return; }
                double pct = parse(a[2]);
                if (Double.isNaN(pct) || pct <= 0 || pct > 1000 || (sub.equals("dump") && pct >= 95)) { pl.send(s, "err.invalid-number"); return; }
                double mult = sub.equals("pump") ? 1 + pct / 100 : 1 - pct / 100;
                run(s, () -> pl.market.applyMultiplier(a[1], mult, false), sub + " " + a[1] + " " + pct + "%");
            }
            case "trend" -> {
                if (a.length != 3) { usage(s, "/gieldaadmin trend <kryptowaluta> <procent, np. 40 lub -30>"); return; }
                double pct = parse(a[2]);
                if (Double.isNaN(pct)) { pl.send(s, "err.invalid-number"); return; }
                run(s, () -> pl.market.setSilentTrend(a[1], pct), "cichy trend " + a[1] + " " + pct + "%");
            }
            case "wymus" -> { pl.forceMarketUpdate(); pl.auditLog(who, "wymuszona aktualizacja rynku"); pl.send(s, "admin.ok"); }
            case "event" -> event(s, a);
            case "reset" -> {
                boolean wipe = a.length >= 3 && a[2].equalsIgnoreCase("wipe");
                if (a.length < 2 || !a[1].equalsIgnoreCase("potwierdz")) {
                    usage(s, "/gieldaadmin reset potwierdz [wipe]   (wipe = także zerowanie kont graczy!)");
                    return;
                }
                pl.io(() -> {
                    pl.market.resetMarket(wipe);
                    pl.announceReset();
                    pl.auditLog(who, "reset rynku, wipe=" + wipe);
                    pl.main(() -> pl.send(s, "admin.ok"));
                });
            }
            case "gracz" -> player(s, a);
            case "wyplaty" -> payouts(s, a);
            case "import" -> new Importer(pl).run(s, a.length > 1 && a[1].equalsIgnoreCase("force"));
            case "reload" -> { pl.loadMessages(); pl.send(s, "admin.ok"); s.sendMessage(GieldaPlugin.color("&7Przeładowano tylko messages.yml. Zmiany w config.yml wymagają restartu serwera.")); }
            default -> usage(s, "/gieldaadmin <cena|pump|dump|trend|wymus|event|reset|gracz|wyplaty|import|reload>");
        }
    }

    private void event(CommandSender s, String[] a) {
        List<SimConfig.EventDef> list = pl.events();
        if (a.length < 2 || a[1].equalsIgnoreCase("lista")) {
            for (int i = 0; i < list.size(); i++) s.sendMessage(GieldaPlugin.color("&7" + i + ": &f" + list.get(i).type() + " &8- &7" + list.get(i).text()));
            usage(s, "/gieldaadmin event <numer>");
            return;
        }
        int i;
        try { i = Integer.parseInt(a[1]); } catch (NumberFormatException e) { pl.send(s, "err.invalid-number"); return; }
        if (i < 0 || i >= list.size()) { pl.send(s, "err.invalid-number"); return; }
        pl.runEvent(i);
        pl.auditLog(s.getName(), "event #" + i);
        pl.send(s, "admin.ok");
    }

    private void player(CommandSender s, String[] a) {
        if (a.length != 5) { usage(s, "/gieldaadmin gracz <nick> <saldo|kryptowaluta> <dodaj|odejmij|ustaw> <wartość>"); return; }
        MarketService.Op op = switch (a[3].toLowerCase(Locale.ROOT)) {
            case "dodaj", "add" -> MarketService.Op.ADD;
            case "odejmij", "sub" -> MarketService.Op.SUB;
            case "ustaw", "set" -> MarketService.Op.SET;
            default -> null;
        };
        if (op == null) { pl.send(s, "err.invalid-number"); return; }
        boolean balance = a[2].equalsIgnoreCase("saldo");
        String desc = "gracz " + a[1] + " " + a[2] + " " + a[3] + " " + a[4];
        if (balance) {
            long c = Fixed.parseMoney(a[4].replace(',', '.'));
            if (c == Fixed.INVALID) { pl.send(s, "err.invalid-number"); return; }
            run(s, () -> pl.market.adminBalance(a[1], op, c), desc);
        } else {
            long q = Fixed.parseQty(a[4].replace(',', '.'));
            if (q == Fixed.INVALID) { pl.send(s, "err.invalid-number"); return; }
            run(s, () -> pl.market.adminHolding(a[1], a[2], op, q), desc);
        }
    }

    private void payouts(CommandSender s, String[] a) {
        if (a.length >= 3 && (a[1].equalsIgnoreCase("zwroc") || a[1].equalsIgnoreCase("zakoncz"))) {
            long id;
            try { id = Long.parseLong(a[2]); } catch (NumberFormatException e) { pl.send(s, "err.invalid-number"); return; }
            boolean refund = a[1].equalsIgnoreCase("zwroc");
            pl.io(() -> {
                boolean exists = pl.market.payouts(MarketService.PAYOUT_SENDING).stream().anyMatch(p -> p.id() == id);
                if (exists) {
                    if (refund) pl.market.failPayout(id); else pl.market.completePayout(id);
                    pl.auditLog(s.getName(), "wypłata #" + id + (refund ? " zwrócona na konto giełdowe" : " oznaczona jako wykonana"));
                }
                pl.main(() -> { if (exists) pl.send(s, "admin.ok"); else s.sendMessage(GieldaPlugin.color("&cNie ma takiej wypłaty w stanie SENDING.")); });
            });
            return;
        }
        pl.async(() -> {
            List<Payout> l = new ArrayList<>(pl.market.payouts(MarketService.PAYOUT_SENDING));
            l.addAll(pl.market.payouts(MarketService.PAYOUT_PENDING));
            return l;
        }, l -> {
            if (l.isEmpty()) s.sendMessage(GieldaPlugin.color("&7Brak oczekujących wypłat."));
            for (Payout p : l) s.sendMessage(GieldaPlugin.color("&7#" + p.id() + " &f" + p.name() + " &e" + Fixed.money(p.amount()) + " " + pl.cur() + " &8[" + p.status() + "]"));
            usage(s, "/gieldaadmin wyplaty <zwroc|zakoncz> <id>  (tylko dla wypłat SENDING po awarii - sprawdź najpierw saldo gracza!)");
        });
    }

    private void run(CommandSender s, java.util.function.Supplier<Result<?>> work, String audit) {
        pl.async(work, r -> {
            if (r.ok) { pl.auditLog(s.getName(), audit); pl.send(s, "admin.ok"); }
            else pl.send(s, "err.generic", "message", r.message);
        });
    }

    private void usage(CommandSender s, String u) { pl.send(s, "admin.usage", "usage", u); }

    private static double parse(String t) {
        try {
            String x = t.trim().replace(',', '.');
            if (!x.matches("-?[0-9]{1,6}(\\.[0-9]{1,4})?")) return Double.NaN;
            return Double.parseDouble(x);
        } catch (NumberFormatException e) { return Double.NaN; }
    }

    // ------------------------------------------------------------------ podpowiedzi

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("gielda") && a.length == 1) out.addAll(List.of("menu", "portfel", "rynek", "link", "saldo", "help"));
        else if (name.equals("gieldaadmin") && s.hasPermission("gielda.admin")) {
            if (a.length == 1) out.addAll(List.of("cena", "pump", "dump", "trend", "wymus", "event", "reset", "gracz", "wyplaty", "import", "reload"));
            else if (a.length == 2 && List.of("cena", "pump", "dump", "trend").contains(a[0].toLowerCase(Locale.ROOT))) pl.cfg.coins.forEach(c -> out.add(c.id()));
            else if (a.length == 2 && a[0].equalsIgnoreCase("reset")) out.add("potwierdz");
            else if (a.length == 3 && a[0].equalsIgnoreCase("reset")) out.add("wipe");
            else if (a.length == 3 && a[0].equalsIgnoreCase("gracz")) { out.add("saldo"); pl.cfg.coins.forEach(c -> out.add(c.id())); }
            else if (a.length == 4 && a[0].equalsIgnoreCase("gracz")) out.addAll(List.of("dodaj", "odejmij", "ustaw"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("wyplaty")) out.addAll(List.of("zwroc", "zakoncz"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("import")) out.add("force");
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
