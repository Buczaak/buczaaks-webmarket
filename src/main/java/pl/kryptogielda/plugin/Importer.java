package pl.kryptogielda.plugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import pl.kryptogielda.core.MarketService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

/**
 * Jednorazowy import z dawnej giełdy (PHP + Skript, baza MySQL): salda graczy i portfele.
 * Zlecenia limit i historia nie są przenoszone (nowy silnik ma inny model cen). Import jest bezpieczny do powtórzenia:
 * konta, które już istnieją, są pomijane.
 */
final class Importer {

    private record OldPlayer(String nick, long balanceCents, Map<String, Long> holdings) {}

    private final GieldaPlugin pl;

    Importer(GieldaPlugin pl) { this.pl = pl; }

    private void say(CommandSender s, String text) { pl.main(() -> s.sendMessage(GieldaPlugin.color(text))); }

    void run(CommandSender s, boolean force) {
        ConfigurationSection c = pl.cfg.importSection;
        if (c == null || !c.getBoolean("enabled", false)) {
            say(s, "&cImport jest wyłączony. Ustaw w config.yml sekcję 'import' (enabled: true, dane starej bazy MySQL), zrestartuj serwer i użyj ponownie. Po imporcie usuń z configu hasło.");
            return;
        }
        final String url = "jdbc:mysql://" + c.getString("host", "127.0.0.1") + ":" + c.getInt("port", 3306) + "/" + c.getString("database", "gielda_mc")
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        final String user = c.getString("user", ""), pass = c.getString("password", "");
        final Set<String> bots = new HashSet<>();
        for (String b : pl.cfg.sim.bots()) bots.add(b.toLowerCase(Locale.ROOT));

        say(s, "&7Import: łączę się ze starą bazą...");
        pl.io(() -> {
            List<OldPlayer> players = new ArrayList<>();
            try (Connection con = DriverManager.getConnection(url, user, pass)) {
                Map<String, Long> balances = new LinkedHashMap<>();
                try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT nick, stan_konta FROM gracze_web")) {
                    while (rs.next()) {
                        String nick = rs.getString(1);
                        if (nick == null || bots.contains(nick.toLowerCase(Locale.ROOT))) continue;
                        balances.put(nick, cents(rs.getBigDecimal(2)));
                    }
                }
                Map<String, Map<String, Long>> hold = new HashMap<>();
                try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT nick, waluta, ilosc FROM portfele_krypto")) {
                    while (rs.next()) {
                        String nick = rs.getString(1);
                        long q = qty(rs.getBigDecimal(3));
                        if (nick == null || q <= 0) continue;
                        hold.computeIfAbsent(nick, k -> new HashMap<>()).merge(rs.getString(2), q, Long::sum);
                    }
                }
                for (Map.Entry<String, Long> e : balances.entrySet())
                    players.add(new OldPlayer(e.getKey(), e.getValue(), hold.getOrDefault(e.getKey(), Map.of())));
                for (Map.Entry<String, Map<String, Long>> e : hold.entrySet())
                    if (!balances.containsKey(e.getKey()) && !bots.contains(e.getKey().toLowerCase(Locale.ROOT)))
                        players.add(new OldPlayer(e.getKey(), 0, e.getValue()));
            } catch (SQLException e) {
                say(s, "&cImport nie powiódł się: " + e.getMessage());
                return;
            }
            say(s, "&7Import: wczytano " + players.size() + " graczy, dopasowuję konta Minecraft...");
            pl.main(() -> resolve(s, players, force));
        });
    }

    /** Główny wątek: zamiana nicków na UUID. */
    private void resolve(CommandSender s, List<OldPlayer> players, boolean force) {
        Map<OldPlayer, UUID> ids = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (OldPlayer p : players) {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(p.nick());
            if (op == null && force) op = Bukkit.getOfflinePlayer(p.nick());
            if (op == null) unknown.add(p.nick()); else ids.put(p, op.getUniqueId());
        }
        pl.io(() -> {
            int ok = 0, existing = 0, skippedCoins = 0;
            for (Map.Entry<OldPlayer, UUID> e : ids.entrySet()) {
                OldPlayer p = e.getKey();
                String owner = e.getValue().toString();
                if (pl.market.account(owner) != null) { existing++; continue; }
                pl.market.ensureAccount(owner, p.nick());
                if (p.balanceCents() > 0) pl.market.adminBalance(p.nick(), MarketService.Op.SET, p.balanceCents());
                for (Map.Entry<String, Long> h : p.holdings().entrySet()) {
                    String coin = pl.market.canon(h.getKey());
                    if (coin == null) { skippedCoins++; continue; }
                    pl.market.adminHolding(p.nick(), coin, MarketService.Op.SET, h.getValue());
                }
                ok++;
            }
            pl.auditLog("import", "zaimportowano " + ok + " kont, pominięto istniejące: " + existing);
            say(s, "&aImport zakończony: &f" + ok + " &akont, już istniejących (pominięte): &f" + existing + "&a, nieznane kryptowaluty (pominięte): &f" + skippedCoins + "&a.");
            if (!unknown.isEmpty()) {
                say(s, "&eNie znaleziono " + unknown.size() + " graczy w pamięci serwera (nigdy nie wchodzili?): &f"
                        + String.join(", ", unknown.subList(0, Math.min(15, unknown.size()))) + (unknown.size() > 15 ? "..." : "")
                        + " &e- użyj &f/gieldaadmin import force&e, jeśli chcesz ich dodać mimo to (tylko dla serwerów offline-mode).");
            }
            say(s, "&7Po imporcie usuń hasło ze starej bazy z config.yml i wyłącz sekcję import.");
        });
    }

    private static long cents(BigDecimal v) { return v == null ? 0 : v.setScale(2, RoundingMode.HALF_DOWN).movePointRight(2).longValue(); }

    private static long qty(BigDecimal v) { return v == null ? 0 : v.setScale(4, RoundingMode.HALF_DOWN).movePointRight(4).longValue(); }
}
