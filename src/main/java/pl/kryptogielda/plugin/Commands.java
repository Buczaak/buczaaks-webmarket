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
                case "gielda", "market" -> gielda(s, a);
                case "wplac", "deposit" -> money(s, a, true);
                case "wyplac", "withdraw" -> money(s, a, false);
                case "gieldaadmin", "marketadmin" -> admin(s, a);
                default -> { return false; }
            }
        } catch (RuntimeException e) {
            pl.getLogger().log(java.util.logging.Level.WARNING, "Command error", e);
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
            case "rynek", "market", "rates" -> pl.gui.open(p, Gui.Type.MARKET, null);
            case "link", "kod", "code" -> link(p);
            case "saldo", "konto", "balance" -> {
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
        if (a.length == 0) { usage(s, adminUsage()); return; }
        String sub = canonSub(a[0]);
        String who = s.getName();
        switch (sub) {
            case "cena" -> {
                if (a.length != 3) { usage(s, pl.t("/gieldaadmin cena <kryptowaluta> <kwota>", "/marketadmin price <coin> <amount>")); return; }
                long c = Fixed.parseMoney(a[2].replace(',', '.'));
                if (c == Fixed.INVALID) { pl.send(s, "err.invalid-number"); return; }
                run(s, () -> pl.market.setPrice(a[1], c), "cena " + a[1] + " = " + a[2]);
            }
            case "pump", "dump" -> {
                if (a.length != 3) { usage(s, "/gieldaadmin " + sub + pl.t(" <kryptowaluta> <procent>", " <coin> <percent>")); return; }
                double pct = parse(a[2]);
                if (Double.isNaN(pct) || pct <= 0 || pct > 1000 || (sub.equals("dump") && pct >= 95)) { pl.send(s, "err.invalid-number"); return; }
                double mult = sub.equals("pump") ? 1 + pct / 100 : 1 - pct / 100;
                run(s, () -> pl.market.applyMultiplier(a[1], mult, false), sub + " " + a[1] + " " + pct + "%");
            }
            case "trend" -> {
                if (a.length != 3) { usage(s, pl.t("/gieldaadmin trend <kryptowaluta> <procent, np. 40 lub -30>", "/marketadmin trend <coin> <percent, e.g. 40 or -30>")); return; }
                double pct = parse(a[2]);
                if (Double.isNaN(pct)) { pl.send(s, "err.invalid-number"); return; }
                run(s, () -> pl.market.setSilentTrend(a[1], pct), "cichy trend " + a[1] + " " + pct + "%");
            }
            case "wymus" -> { pl.forceMarketUpdate(); pl.auditLog(who, "wymuszona aktualizacja rynku"); pl.send(s, "admin.ok"); }
            case "event" -> event(s, a);
            case "reset" -> {
                boolean wipe = a.length >= 3 && a[2].equalsIgnoreCase("wipe");
                if (a.length < 2 || !(a[1].equalsIgnoreCase("potwierdz") || a[1].equalsIgnoreCase("confirm"))) {
                    usage(s, pl.t("/gieldaadmin reset potwierdz [wipe]   (wipe = także zerowanie kont graczy!)", "/marketadmin reset confirm [wipe]   (wipe = also zeroes player accounts!)"));
                    return;
                }
                pl.io(() -> {
                    pl.market.resetMarket(wipe);
                    pl.announceReset();
                    pl.auditLog(who, "reset rynku, wipe=" + wipe);
                    pl.main(() -> pl.send(s, "admin.ok"));
                });
            }
            case "moneta" -> coin(s, a);
            case "gracz" -> player(s, a);
            case "wyplaty" -> payouts(s, a);
            case "import" -> new Importer(pl).run(s, a.length > 1 && a[1].equalsIgnoreCase("force"));
            case "reload" -> { pl.loadMessages(); pl.send(s, "admin.ok"); s.sendMessage(GieldaPlugin.color(pl.t("&7Przeładowano tylko plik komunikatów. Zmiany w config.yml wymagają restartu serwera.", "&7Only the messages file was reloaded. Changes to config.yml need a server restart."))); }
            default -> usage(s, adminUsage());
        }
    }

    private String adminUsage() {
        return pl.t("/gieldaadmin <cena|pump|dump|trend|wymus|event|moneta|reset|gracz|wyplaty|import|reload>",
                "/marketadmin <price|pump|dump|trend|force|event|coin|reset|player|payouts|import|reload>");
    }

    /** English names of subcommands map to the canonical (Polish) ones. */
    private static String canonSub(String x) {
        String t = x.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "price" -> "cena";
            case "force" -> "wymus";
            case "player" -> "gracz";
            case "payouts" -> "wyplaty";
            case "coin", "coins" -> "moneta";
            default -> t;
        };
    }

    // --- własne kryptowaluty: /gieldaadmin moneta lista | dodaj <nazwa> <cena> [min max płynność ikona] | usun <nazwa>
    private void coin(CommandSender s, String[] a) {
        String op = a.length < 2 ? "list" : a[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list", "lista" -> {
                for (CoinDef d : pl.market.coinDefs()) {
                    s.sendMessage(GieldaPlugin.color("&7" + d.id() + " &8- &f" + Fixed.money(d.startPrice()) + " " + pl.cur()
                            + " &8(" + d.volMin() + "-" + d.volMax() + ", " + d.icon() + ")" + (pl.market.isCustomCoin(d.id()) ? " &b[custom]" : " &8[config.yml]")));
                }
                usage(s, pl.t("/gieldaadmin moneta <lista|dodaj|edytuj|usun> ...", "/marketadmin coin <list|add|edit|remove> ..."));
            }
            case "add", "dodaj", "edit", "edytuj" -> {
                boolean edit = op.startsWith("edit") || op.equals("edytuj");
                if (a.length < 4) {
                    usage(s, pl.t("/gieldaadmin moneta " + (edit ? "edytuj" : "dodaj") + " <nazwa> <cena startowa> [zmienność-min zmienność-max płynność ikona]",
                            "/marketadmin coin " + (edit ? "edit" : "add") + " <name> <start price> [volatility-min volatility-max liquidity icon]"));
                    return;
                }
                long price = Fixed.parseMoney(a[3].replace(',', '.'));
                double lo = a.length > 4 ? parse(a[4]) : 0.96, hi = a.length > 5 ? parse(a[5]) : 1.04, liq = a.length > 6 ? parse(a[6]) : 0;
                String icon = a.length > 7 ? a[7] : "GOLD_NUGGET";
                if (price == Fixed.INVALID || Double.isNaN(lo) || Double.isNaN(hi) || Double.isNaN(liq)) { pl.send(s, "err.invalid-number"); return; }
                CoinDef d = new CoinDef(a[2], price, icon, lo, hi, liq);
                run(s, () -> edit ? pl.market.updateCustomCoin(d) : pl.market.addCustomCoin(d), "moneta " + op + " " + a[2]);
            }
            case "remove", "usun", "usuń", "delete" -> {
                if (a.length < 3) { usage(s, pl.t("/gieldaadmin moneta usun <nazwa>", "/marketadmin coin remove <name>")); return; }
                run(s, () -> pl.market.removeCustomCoin(a[2]), "moneta usun " + a[2]);
            }
            default -> usage(s, pl.t("/gieldaadmin moneta <lista|dodaj|edytuj|usun> ...", "/marketadmin coin <list|add|edit|remove> ..."));
        }
    }

    private void event(CommandSender s, String[] a) {
        List<SimConfig.EventDef> list = pl.events();
        String op = a.length < 2 ? "list" : a[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list", "lista" -> {
                int cfgCount = pl.sim.configEventCount();
                for (int i = 0; i < list.size(); i++) {
                    s.sendMessage(GieldaPlugin.color("&7" + i + ": &f" + list.get(i).type() + " &8- &7" + list.get(i).text() + (i >= cfgCount ? " &b[custom]" : "")));
                }
                usage(s, pl.t("/gieldaadmin event <numer|dodaj|usun|import>", "/marketadmin event <number|add|remove|import>"));
            }
            case "import" -> pl.io(() -> {
                int n = pl.sim.importDefaultEvents(pl.cfg.eventsLanguage);
                pl.auditLog(s.getName(), "import default events: " + n);
                pl.main(() -> s.sendMessage(GieldaPlugin.color(pl.t("&aDodano domyślnych eventów: &e" + n, "&aDefault events added: &e" + n))));
            });
            case "add", "dodaj" -> {
                // event dodaj <typ> <moneta|-> <mnożnik> <waga> <tekst...>
                if (a.length < 7) {
                    usage(s, pl.t("/gieldaadmin event dodaj <typ> <moneta|-> <mnożnik> <waga> <tekst>", "/marketadmin event add <type> <coin|-> <multiplier> <weight> <text>"));
                    return;
                }
                double mult = parse(a[4]);
                int weight;
                try { weight = Integer.parseInt(a[5]); } catch (NumberFormatException e) { pl.send(s, "err.invalid-number"); return; }
                if (Double.isNaN(mult)) { pl.send(s, "err.invalid-number"); return; }
                String coin = a[3].equals("-") ? "" : a[3];
                String text = String.join(" ", java.util.Arrays.copyOfRange(a, 6, a.length));
                final double m2 = mult; final int w2 = weight;
                run(s, () -> pl.market.addCustomEvent(a[2], text, coin, m2, w2, m2 >= 1 ? "&a" : "&c"), "event add " + a[2]);
            }
            case "remove", "usun", "usuń", "delete" -> {
                int i = a.length > 2 ? parseInt(a[2]) : -1;
                int cfgCount = pl.sim.configEventCount();
                if (i < cfgCount || i >= list.size()) {
                    s.sendMessage(GieldaPlugin.color(pl.t("&cMożna usuwać tylko własne eventy (numery od " + cfgCount + "). Eventy z config.yml usuń w pliku.",
                            "&cOnly custom events can be removed (numbers from " + cfgCount + "). Remove config.yml events in the file.")));
                    return;
                }
                long id = pl.market.customEvents().get(i - cfgCount).id();
                run(s, () -> pl.market.deleteCustomEvent(id), "event remove #" + i);
            }
            default -> {
                int i = parseInt(a[1]);
                if (i < 0 || i >= list.size()) { pl.send(s, "err.invalid-number"); return; }
                pl.runEvent(i);
                pl.auditLog(s.getName(), "event #" + i);
                pl.send(s, "admin.ok");
            }
        }
    }

    private static int parseInt(String t) {
        try { return Integer.parseInt(t.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private void player(CommandSender s, String[] a) {
        if (a.length != 5) { usage(s, pl.t("/gieldaadmin gracz <nick> <saldo|kryptowaluta> <dodaj|odejmij|ustaw> <wartość>", "/marketadmin player <nick> <balance|coin> <add|sub|set> <value>")); return; }
        MarketService.Op op = switch (a[3].toLowerCase(Locale.ROOT)) {
            case "dodaj", "add" -> MarketService.Op.ADD;
            case "odejmij", "sub" -> MarketService.Op.SUB;
            case "ustaw", "set" -> MarketService.Op.SET;
            default -> null;
        };
        if (op == null) { pl.send(s, "err.invalid-number"); return; }
        boolean balance = a[2].equalsIgnoreCase("saldo") || a[2].equalsIgnoreCase("balance");
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
        if (a.length >= 3 && (a[1].equalsIgnoreCase("zwroc") || a[1].equalsIgnoreCase("zakoncz") || a[1].equalsIgnoreCase("refund") || a[1].equalsIgnoreCase("finish"))) {
            long id;
            try { id = Long.parseLong(a[2]); } catch (NumberFormatException e) { pl.send(s, "err.invalid-number"); return; }
            boolean refund = a[1].equalsIgnoreCase("zwroc") || a[1].equalsIgnoreCase("refund");
            pl.io(() -> {
                boolean exists = pl.market.payouts(MarketService.PAYOUT_SENDING).stream().anyMatch(p -> p.id() == id);
                if (exists) {
                    if (refund) pl.market.failPayout(id); else pl.market.completePayout(id);
                    pl.auditLog(s.getName(), "wypłata #" + id + (refund ? " zwrócona na konto giełdowe" : " oznaczona jako wykonana"));
                }
                pl.main(() -> { if (exists) pl.send(s, "admin.ok"); else s.sendMessage(GieldaPlugin.color(pl.t("&cNie ma takiej wypłaty w stanie SENDING.", "&cNo such payout in SENDING state."))); });
            });
            return;
        }
        pl.async(() -> {
            List<Payout> l = new ArrayList<>(pl.market.payouts(MarketService.PAYOUT_SENDING));
            l.addAll(pl.market.payouts(MarketService.PAYOUT_PENDING));
            return l;
        }, l -> {
            if (l.isEmpty()) s.sendMessage(GieldaPlugin.color(pl.t("&7Brak oczekujących wypłat.", "&7No pending payouts.")));
            for (Payout p : l) s.sendMessage(GieldaPlugin.color("&7#" + p.id() + " &f" + p.name() + " &e" + Fixed.money(p.amount()) + " " + pl.cur() + " &8[" + p.status() + "]"));
            usage(s, pl.t("/gieldaadmin wyplaty <zwroc|zakoncz> <id>  (tylko dla wypłat SENDING po awarii - sprawdź najpierw saldo gracza!)", "/marketadmin payouts <refund|finish> <id>  (only for SENDING payouts after a crash - check the player balance first!)"));
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
        boolean en = pl.cfg.language.equals("en");
        List<String> coinIds = new ArrayList<>();
        pl.market.coinDefs().forEach(c -> coinIds.add(c.id()));
        if ((name.equals("gielda") || name.equals("market")) && a.length == 1)
            out.addAll(en ? List.of("menu", "portfolio", "rates", "link", "balance", "help") : List.of("menu", "portfel", "rynek", "link", "saldo", "help"));
        else if ((name.equals("gieldaadmin") || name.equals("marketadmin")) && s.hasPermission("gielda.admin")) {
            String sub = a.length > 0 ? canonSub(a[0]) : "";
            if (a.length == 1) out.addAll(en ? List.of("price", "pump", "dump", "trend", "force", "event", "coin", "reset", "player", "payouts", "import", "reload")
                    : List.of("cena", "pump", "dump", "trend", "wymus", "event", "moneta", "reset", "gracz", "wyplaty", "import", "reload"));
            else if (a.length == 2 && List.of("cena", "pump", "dump", "trend").contains(sub)) out.addAll(coinIds);
            else if (a.length == 2 && sub.equals("reset")) out.add(en ? "confirm" : "potwierdz");
            else if (a.length == 3 && sub.equals("reset")) out.add("wipe");
            else if (a.length == 2 && sub.equals("moneta")) out.addAll(en ? List.of("list", "add", "edit", "remove") : List.of("lista", "dodaj", "edytuj", "usun"));
            else if (a.length == 3 && sub.equals("moneta") && List.of("edit", "edytuj", "remove", "usun").contains(a[1].toLowerCase(Locale.ROOT)))
                coinIds.stream().filter(pl.market::isCustomCoin).forEach(out::add);
            else if (a.length == 2 && sub.equals("event")) out.addAll(en ? List.of("list", "add", "remove", "import") : List.of("lista", "dodaj", "usun", "import"));
            else if (a.length == 4 && sub.equals("event") && List.of("add", "dodaj").contains(a[1].toLowerCase(Locale.ROOT))) { out.add("-"); out.addAll(coinIds); }
            else if (a.length == 3 && sub.equals("gracz")) { out.add(en ? "balance" : "saldo"); out.addAll(coinIds); }
            else if (a.length == 4 && sub.equals("gracz")) out.addAll(en ? List.of("add", "sub", "set") : List.of("dodaj", "odejmij", "ustaw"));
            else if (a.length == 2 && sub.equals("wyplaty")) out.addAll(en ? List.of("refund", "finish") : List.of("zwroc", "zakoncz"));
            else if (a.length == 2 && sub.equals("import")) out.add("force");
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
