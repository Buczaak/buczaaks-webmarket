package pl.kryptogielda.store;

import pl.kryptogielda.core.CoinDef;
import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.core.Side;
import pl.kryptogielda.core.Store;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Zapis w bazie SQL przez JDBC (SQLite domyślnie, MySQL/MariaDB opcjonalnie).
 * Cały dostęp jest szeregowany (synchronized), więc wystarczy jedno połączenie.
 * SQL jest celowo prosty i przenośny między silnikami (bez upsertów specyficznych dla bazy).
 */
public final class JdbcStore implements Store {

    private final String url;
    private final String user;
    private final String pass;
    private final boolean sqlite;
    private Connection conn;

    public JdbcStore(String url, String user, String pass) {
        this.url = url;
        this.user = user;
        this.pass = pass;
        this.sqlite = url.startsWith("jdbc:sqlite");
        synchronized (this) {
            connect();
            createSchema();
        }
    }

    // ------------------------------------------------------------------ połączenie

    private void connect() {
        try {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
            conn = (user == null || user.isEmpty()) ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, pass);
            if (sqlite) {
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA busy_timeout=5000");
                    st.execute("PRAGMA synchronous=NORMAL");
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Nie można połączyć z bazą danych (" + safeUrl() + "): " + e.getMessage(), e);
        }
    }

    private String safeUrl() {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    private void ensureConnection() {
        try {
            if (conn == null || conn.isClosed() || !conn.isValid(2)) connect();
        } catch (SQLException e) {
            connect();
        }
    }

    private void createSchema() {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS kg_seq (name VARCHAR(32) NOT NULL PRIMARY KEY, val BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_account (owner VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                        + "bot INTEGER NOT NULL, balance BIGINT NOT NULL, created BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_coin (id VARCHAR(64) NOT NULL PRIMARY KEY, price BIGINT NOT NULL, "
                        + "trend VARCHAR(64) NOT NULL, silent_bp BIGINT NOT NULL, updated BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_holding (owner VARCHAR(64) NOT NULL, coin VARCHAR(64) NOT NULL, "
                        + "qty BIGINT NOT NULL, cost BIGINT NOT NULL, PRIMARY KEY (owner, coin))",
                "CREATE TABLE IF NOT EXISTS kg_order (id BIGINT NOT NULL PRIMARY KEY, owner VARCHAR(64) NOT NULL, "
                        + "name VARCHAR(64) NOT NULL, side VARCHAR(8) NOT NULL, coin VARCHAR(64) NOT NULL, qty BIGINT NOT NULL, "
                        + "limit_price BIGINT NOT NULL, locked BIGINT NOT NULL, created BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_trade (id BIGINT NOT NULL PRIMARY KEY, ts BIGINT NOT NULL, owner VARCHAR(64) NOT NULL, "
                        + "name VARCHAR(64) NOT NULL, side VARCHAR(8) NOT NULL, coin VARCHAR(64) NOT NULL, qty BIGINT NOT NULL, "
                        + "value BIGINT NOT NULL, source VARCHAR(16) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_price (id BIGINT NOT NULL PRIMARY KEY, coin VARCHAR(64) NOT NULL, "
                        + "ts BIGINT NOT NULL, price BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_news (id BIGINT NOT NULL PRIMARY KEY, ts BIGINT NOT NULL, "
                        + "type VARCHAR(48) NOT NULL, text VARCHAR(512) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_payout (id BIGINT NOT NULL PRIMARY KEY, owner VARCHAR(64) NOT NULL, "
                        + "name VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, status VARCHAR(12) NOT NULL, created BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_custom_coin (id VARCHAR(64) NOT NULL PRIMARY KEY, start_price BIGINT NOT NULL, "
                        + "icon VARCHAR(64) NOT NULL, vol_min DOUBLE PRECISION NOT NULL, vol_max DOUBLE PRECISION NOT NULL, "
                        + "liquidity DOUBLE PRECISION NOT NULL)",
                "CREATE TABLE IF NOT EXISTS kg_custom_event (id BIGINT NOT NULL PRIMARY KEY, type VARCHAR(48) NOT NULL, "
                        + "text VARCHAR(512) NOT NULL, coin VARCHAR(64) NOT NULL, multiplier DOUBLE PRECISION NOT NULL, "
                        + "weight INTEGER NOT NULL, color VARCHAR(16) NOT NULL)"
        };
        String[] indexes = {
                "CREATE INDEX kg_price_coin_ts ON kg_price (coin, ts)",
                "CREATE INDEX kg_trade_owner ON kg_trade (owner, coin)",
                "CREATE INDEX kg_order_owner ON kg_order (owner)",
                "CREATE INDEX kg_account_name ON kg_account (name)"
        };
        try (Statement st = conn.createStatement()) {
            for (String s : ddl) st.execute(s);
            for (String s : indexes) {
                try { st.execute(s); } catch (SQLException ignored) { /* indeks już istnieje */ }
            }
        } catch (SQLException e) {
            throw new StoreException("Nie można utworzyć tabel: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ transakcje

    @Override
    public synchronized <T> T inTx(TxWork<T> work) {
        ensureConnection();
        try {
            conn.setAutoCommit(false);
            T result;
            try {
                result = work.run(new JdbcTx());
                conn.commit();
            } catch (Throwable t) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                if (t instanceof StoreException se) throw se;
                if (t instanceof RuntimeException re) throw re;
                throw new StoreException("Błąd bazy danych: " + t.getMessage(), t);
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("Błąd bazy danych: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
            conn = null;
        }
    }

    // ------------------------------------------------------------------ operacje

    private final class JdbcTx implements Tx {

        // --- pomocnicze

        private PreparedStatement ps(String sql, Object... args) throws SQLException {
            PreparedStatement p = conn.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof Long l) p.setLong(i + 1, l);
                else if (a instanceof Integer n) p.setInt(i + 1, n);
                else if (a instanceof Double dd) p.setDouble(i + 1, dd);
                else p.setString(i + 1, (String) a);
            }
            return p;
        }

        private int update(String sql, Object... args) {
            try (PreparedStatement p = ps(sql, args)) {
                return p.executeUpdate();
            } catch (SQLException e) {
                throw new StoreException("SQL: " + e.getMessage(), e);
            }
        }

        private <R> List<R> query(String sql, RowMapper<R> m, Object... args) {
            try (PreparedStatement p = ps(sql, args); ResultSet rs = p.executeQuery()) {
                List<R> out = new ArrayList<>();
                while (rs.next()) out.add(m.map(rs));
                return out;
            } catch (SQLException e) {
                throw new StoreException("SQL: " + e.getMessage(), e);
            }
        }

        private <R> R one(String sql, RowMapper<R> m, Object... args) {
            List<R> l = query(sql, m, args);
            return l.isEmpty() ? null : l.get(0);
        }

        // --- sekwencje

        @Override
        public long nextId(String seq) {
            Long cur = one("SELECT val FROM kg_seq WHERE name = ?", rs -> rs.getLong(1), seq);
            if (cur == null) {
                update("INSERT INTO kg_seq (name, val) VALUES (?, ?)", seq, 1L);
                return 1L;
            }
            update("UPDATE kg_seq SET val = ? WHERE name = ?", cur + 1, seq);
            return cur + 1;
        }

        // --- konta

        private final RowMapper<Account> accountMapper = rs ->
                new Account(rs.getString(1), rs.getString(2), rs.getInt(3) != 0, rs.getLong(4), rs.getLong(5));
        private static final String ACC_COLS = "owner, name, bot, balance, created";

        @Override
        public Account account(String owner) {
            return one("SELECT " + ACC_COLS + " FROM kg_account WHERE owner = ?", accountMapper, owner);
        }

        @Override
        public Account accountByName(String name) {
            return one("SELECT " + ACC_COLS + " FROM kg_account WHERE LOWER(name) = LOWER(?) AND bot = 0 ORDER BY created LIMIT 1",
                    accountMapper, name);
        }

        @Override
        public void saveAccount(Account a) {
            int n = update("UPDATE kg_account SET name = ?, bot = ?, balance = ? WHERE owner = ?",
                    a.name(), a.bot() ? 1 : 0, a.balance(), a.owner());
            if (n == 0) {
                update("INSERT INTO kg_account (owner, name, bot, balance, created) VALUES (?, ?, ?, ?, ?)",
                        a.owner(), a.name(), a.bot() ? 1 : 0, a.balance(), a.created());
            }
        }

        @Override
        public List<Account> accounts() {
            return query("SELECT " + ACC_COLS + " FROM kg_account", accountMapper);
        }

        // --- portfele

        private final RowMapper<Holding> holdingMapper = rs ->
                new Holding(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4));

        @Override
        public Holding holding(String owner, String coin) {
            return one("SELECT owner, coin, qty, cost FROM kg_holding WHERE owner = ? AND coin = ?", holdingMapper, owner, coin);
        }

        @Override
        public void saveHolding(Holding h) {
            if (h.qty() <= 0) {
                update("DELETE FROM kg_holding WHERE owner = ? AND coin = ?", h.owner(), h.coin());
                return;
            }
            int n = update("UPDATE kg_holding SET qty = ?, cost = ? WHERE owner = ? AND coin = ?",
                    h.qty(), h.cost(), h.owner(), h.coin());
            if (n == 0) {
                update("INSERT INTO kg_holding (owner, coin, qty, cost) VALUES (?, ?, ?, ?)",
                        h.owner(), h.coin(), h.qty(), h.cost());
            }
        }

        @Override
        public List<Holding> holdings(String owner) {
            return query("SELECT owner, coin, qty, cost FROM kg_holding WHERE owner = ? ORDER BY coin", holdingMapper, owner);
        }

        @Override
        public List<Holding> allHoldings() {
            return query("SELECT owner, coin, qty, cost FROM kg_holding", holdingMapper);
        }

        // --- kryptowaluty

        private final RowMapper<CoinState> coinMapper = rs ->
                new CoinState(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5));

        @Override
        public CoinState coin(String id) {
            return one("SELECT id, price, trend, silent_bp, updated FROM kg_coin WHERE id = ?", coinMapper, id);
        }

        @Override
        public List<CoinState> coins() {
            return query("SELECT id, price, trend, silent_bp, updated FROM kg_coin ORDER BY id", coinMapper);
        }

        @Override
        public void saveCoin(CoinState c) {
            int n = update("UPDATE kg_coin SET price = ?, trend = ?, silent_bp = ?, updated = ? WHERE id = ?",
                    c.price(), c.trend(), c.silentBp(), c.updated(), c.id());
            if (n == 0) {
                update("INSERT INTO kg_coin (id, price, trend, silent_bp, updated) VALUES (?, ?, ?, ?, ?)",
                        c.id(), c.price(), c.trend(), c.silentBp(), c.updated());
            }
        }

        @Override
        public void deleteCoinData(String id) {
            update("DELETE FROM kg_coin WHERE id = ?", id);
            update("DELETE FROM kg_price WHERE coin = ?", id);
        }

        // --- własne kryptowaluty i eventy (dodane z panelu admina)

        @Override
        public List<CoinDef> customCoins() {
            return query("SELECT id, start_price, icon, vol_min, vol_max, liquidity FROM kg_custom_coin ORDER BY id",
                    rs -> new CoinDef(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
        }

        @Override
        public void saveCustomCoin(CoinDef d) {
            int n = update("UPDATE kg_custom_coin SET start_price = ?, icon = ?, vol_min = ?, vol_max = ?, liquidity = ? WHERE id = ?",
                    d.startPrice(), d.icon(), d.volMin(), d.volMax(), d.liquidity(), d.id());
            if (n == 0) {
                update("INSERT INTO kg_custom_coin (id, start_price, icon, vol_min, vol_max, liquidity) VALUES (?, ?, ?, ?, ?, ?)",
                        d.id(), d.startPrice(), d.icon(), d.volMin(), d.volMax(), d.liquidity());
            }
        }

        @Override
        public void deleteCustomCoin(String id) {
            update("DELETE FROM kg_custom_coin WHERE id = ?", id);
        }

        @Override
        public List<CustomEvent> customEvents() {
            return query("SELECT id, type, text, coin, multiplier, weight, color FROM kg_custom_event ORDER BY id",
                    rs -> new CustomEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getInt(6), rs.getString(7)));
        }

        @Override
        public void saveCustomEvent(CustomEvent e) {
            int n = update("UPDATE kg_custom_event SET type = ?, text = ?, coin = ?, multiplier = ?, weight = ?, color = ? WHERE id = ?",
                    e.type(), e.text(), e.coin(), e.multiplier(), e.weight(), e.color(), e.id());
            if (n == 0) {
                update("INSERT INTO kg_custom_event (id, type, text, coin, multiplier, weight, color) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        e.id(), e.type(), e.text(), e.coin(), e.multiplier(), e.weight(), e.color());
            }
        }

        @Override
        public void deleteCustomEvent(long id) {
            update("DELETE FROM kg_custom_event WHERE id = ?", id);
        }

        // --- zlecenia

        private final RowMapper<Order> orderMapper = rs ->
                new Order(rs.getLong(1), rs.getString(2), rs.getString(3), Side.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9));
        private static final String ORD_COLS = "id, owner, name, side, coin, qty, limit_price, locked, created";

        @Override
        public void insertOrder(Order o) {
            update("INSERT INTO kg_order (" + ORD_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    o.id(), o.owner(), o.name(), o.side().name(), o.coin(), o.qty(), o.limit(), o.locked(), o.created());
        }

        @Override
        public void deleteOrder(long id) {
            update("DELETE FROM kg_order WHERE id = ?", id);
        }

        @Override
        public Order order(long id) {
            return one("SELECT " + ORD_COLS + " FROM kg_order WHERE id = ?", orderMapper, id);
        }

        @Override
        public List<Order> orders(String owner) {
            return query("SELECT " + ORD_COLS + " FROM kg_order WHERE owner = ? ORDER BY id DESC", orderMapper, owner);
        }

        @Override
        public List<Order> allOrders() {
            return query("SELECT " + ORD_COLS + " FROM kg_order ORDER BY id", orderMapper);
        }

        // --- transakcje

        private final RowMapper<Trade> tradeMapper = rs ->
                new Trade(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), Side.valueOf(rs.getString(5)),
                        rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getString(9));
        private static final String TR_COLS = "id, ts, owner, name, side, coin, qty, value, source";

        @Override
        public void insertTrade(Trade t) {
            update("INSERT INTO kg_trade (" + TR_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    t.id(), t.ts(), t.owner(), t.name(), t.side().name(), t.coin(), t.qty(), t.value(), t.source());
        }

        @Override
        public List<Trade> recentTrades(int limit) {
            return query("SELECT " + TR_COLS + " FROM kg_trade ORDER BY id DESC LIMIT ?", tradeMapper, limit);
        }

        @Override
        public List<Trade> tradesOf(String owner, String coin, int limit) {
            if (coin == null) {
                return query("SELECT " + TR_COLS + " FROM kg_trade WHERE owner = ? ORDER BY id DESC LIMIT ?", tradeMapper, owner, limit);
            }
            return query("SELECT " + TR_COLS + " FROM kg_trade WHERE owner = ? AND coin = ? ORDER BY id DESC LIMIT ?",
                    tradeMapper, owner, coin, limit);
        }

        // --- ceny

        @Override
        public void insertPrice(String coin, long ts, long price) {
            long id = nextId("price");
            update("INSERT INTO kg_price (id, coin, ts, price) VALUES (?, ?, ?, ?)", id, coin, ts, price);
        }

        @Override
        public List<PricePoint> prices(String coin, long sinceTs) {
            return query("SELECT ts, price FROM kg_price WHERE coin = ? AND ts >= ? ORDER BY ts, id",
                    rs -> new PricePoint(rs.getLong(1), rs.getLong(2)), coin, sinceTs);
        }

        @Override
        public void pruneOlderThan(long ts) {
            // zawsze zostaw ostatni punkt każdej waluty, żeby wykres miał punkt odniesienia
            update("DELETE FROM kg_price WHERE ts < ? AND id NOT IN "
                    + "(SELECT m FROM (SELECT MAX(id) AS m FROM kg_price GROUP BY coin) AS t)", ts);
        }

        @Override
        public long[] priceStats(String coin, long sinceTs) {
            Long open = one("SELECT price FROM kg_price WHERE coin = ? AND ts >= ? ORDER BY ts, id LIMIT 1",
                    rs -> rs.getLong(1), coin, sinceTs);
            if (open == null) return null;
            long[] hl = one("SELECT MAX(price), MIN(price) FROM kg_price WHERE coin = ? AND ts >= ?",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2)}, coin, sinceTs);
            return new long[]{open, hl == null ? open : hl[0], hl == null ? open : hl[1]};
        }

        @Override
        public int thinPrices(long olderThanTs, long bucketMs) {
            record Row(long id, long ts, long price) {}
            int removed = 0;
            for (String coin : query("SELECT DISTINCT coin FROM kg_price", rs -> rs.getString(1))) {
                List<Row> rows = query("SELECT id, ts, price FROM kg_price WHERE coin = ? AND ts < ? ORDER BY ts, id",
                        rs -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3)), coin, olderThanTs);
                List<Long> drop = new ArrayList<>();
                int i = 0;
                while (i < rows.size()) {
                    long b = Math.floorDiv(rows.get(i).ts(), bucketMs);
                    int j = i;
                    Row first = rows.get(i), last = first, hi = first, lo = first;
                    while (j < rows.size() && Math.floorDiv(rows.get(j).ts(), bucketMs) == b) {
                        Row r = rows.get(j);
                        last = r;
                        if (r.price() > hi.price()) hi = r;
                        if (r.price() < lo.price()) lo = r;
                        j++;
                    }
                    for (int k = i; k < j; k++) {
                        Row r = rows.get(k);
                        if (r != first && r != last && r != hi && r != lo) drop.add(r.id());
                    }
                    i = j;
                }
                for (int from = 0; from < drop.size(); from += 200) {
                    List<Long> part = drop.subList(from, Math.min(drop.size(), from + 200));
                    StringBuilder sql = new StringBuilder("DELETE FROM kg_price WHERE id IN (");
                    for (int k = 0; k < part.size(); k++) sql.append(k == 0 ? "?" : ",?");
                    sql.append(")");
                    removed += update(sql.toString(), part.toArray());
                }
            }
            return removed;
        }

        // --- newsy

        @Override
        public void insertNews(NewsItem n) {
            String text = n.text().length() > 500 ? n.text().substring(0, 500) : n.text();
            update("INSERT INTO kg_news (id, ts, type, text) VALUES (?, ?, ?, ?)", n.id(), n.ts(), n.type(), text);
        }

        @Override
        public List<NewsItem> news(int limit) {
            return query("SELECT id, ts, type, text FROM kg_news ORDER BY id DESC LIMIT ?",
                    rs -> new NewsItem(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), limit);
        }

        // --- wypłaty (kolejka)

        private final RowMapper<Payout> payoutMapper = rs ->
                new Payout(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6));

        @Override
        public void insertPayout(Payout p) {
            update("INSERT INTO kg_payout (id, owner, name, amount, status, created) VALUES (?, ?, ?, ?, ?, ?)",
                    p.id(), p.owner(), p.name(), p.amount(), p.status(), p.created());
        }

        @Override
        public List<Payout> payouts(String status) {
            return query("SELECT id, owner, name, amount, status, created FROM kg_payout WHERE status = ? ORDER BY id",
                    payoutMapper, status);
        }

        @Override
        public void setPayoutStatus(long id, String status) {
            update("UPDATE kg_payout SET status = ? WHERE id = ?", status, id);
        }

        @Override
        public void deletePayout(long id) {
            update("DELETE FROM kg_payout WHERE id = ?", id);
        }

        // --- czyszczenie

        @Override
        public void wipeHistory() {
            update("DELETE FROM kg_price");
            update("DELETE FROM kg_trade");
            update("DELETE FROM kg_news");
        }

        @Override
        public void wipePlayers() {
            update("DELETE FROM kg_holding");
            update("DELETE FROM kg_order");
            update("UPDATE kg_account SET balance = 0 WHERE bot = 0");
        }
    }

    @FunctionalInterface
    private interface RowMapper<R> {
        R map(ResultSet rs) throws SQLException;
    }
}
