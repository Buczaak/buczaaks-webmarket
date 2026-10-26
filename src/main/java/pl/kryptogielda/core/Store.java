package pl.kryptogielda.core;

import pl.kryptogielda.core.Model.*;

import java.util.List;

/**
 * Warstwa trwałego zapisu. Wszystkie operacje wykonywane są w transakcji ({@link #inTx}).
 * Implementacja: {@code pl.kryptogielda.store.JdbcStore} (SQLite / MySQL).
 */
public interface Store extends AutoCloseable {

    /** Wykonuje pracę w jednej transakcji; wyjątek = wycofanie wszystkich zmian. */
    <T> T inTx(TxWork<T> work);

    @FunctionalInterface
    interface TxWork<T> {
        T run(Tx tx) throws Exception;
    }

    class StoreException extends RuntimeException {
        public StoreException(String msg, Throwable cause) { super(msg, cause); }
    }

    @Override
    void close();

    interface Tx {
        long nextId(String seq);

        Account account(String owner);
        Account accountByName(String name);
        void saveAccount(Account a);
        List<Account> accounts();

        Holding holding(String owner, String coin);
        /** qty == 0 usuwa wiersz. */
        void saveHolding(Holding h);
        List<Holding> holdings(String owner);
        List<Holding> allHoldings();

        CoinState coin(String id);
        List<CoinState> coins();
        void saveCoin(CoinState c);
        /** Usuwa stan i historię cen kryptowaluty. */
        void deleteCoinData(String id);

        /** Kryptowaluty dodane przez administratora (definicje). */
        List<CoinDef> customCoins();
        void saveCustomCoin(CoinDef d);
        void deleteCustomCoin(String id);

        /** Eventy dodane przez administratora. */
        List<CustomEvent> customEvents();
        void saveCustomEvent(CustomEvent e);
        void deleteCustomEvent(long id);

        void insertOrder(Order o);
        void deleteOrder(long id);
        Order order(long id);
        List<Order> orders(String owner);
        List<Order> allOrders();

        void insertTrade(Trade t);
        List<Trade> recentTrades(int limit);
        List<Trade> tradesOf(String owner, String coin, int limit);

        void insertPrice(String coin, long ts, long price);
        List<PricePoint> prices(String coin, long sinceTs);
        void pruneOlderThan(long ts);
        /** Otwarcie, maksimum i minimum ceny (mikro-jednostki) od {@code sinceTs}; null, gdy brak punktów. */
        long[] priceStats(String coin, long sinceTs);
        /** Przerzedza punkty starsze niż {@code olderThanTs}: w każdym przedziale zostają pierwszy, ostatni, najwyższy i najniższy. */
        int thinPrices(long olderThanTs, long bucketMs);

        void insertNews(NewsItem n);
        List<NewsItem> news(int limit);

        void insertPayout(Payout p);
        List<Payout> payouts(String status);
        void setPayoutStatus(long id, String status);
        void deletePayout(long id);

        /** Czyści historię rynku (ceny, transakcje, newsy). */
        void wipeHistory();
        /** Czyści portfele, zlecenia i zeruje salda graczy (nie botów). */
        void wipePlayers();
    }
}
