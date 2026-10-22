package pl.kryptogielda.core;

/** Proste, niezmienne obiekty danych używane w całym pluginie. */
public final class Model {
    private Model() {}

    public record Account(String owner, String name, boolean bot, long balance, long created) {
        public Account withBalance(long b) { return new Account(owner, name, bot, b, created); }
        public Account withName(String n) { return new Account(owner, n, bot, balance, created); }
    }

    public record Holding(String owner, String coin, long qty, long cost) {}

    /** Stan rynku dla jednej kryptowaluty. */
    public record CoinState(String id, long price, String trend, long silentBp, long updated) {
        public CoinState with(long newPrice, String newTrend, long now) {
            return new CoinState(id, newPrice, newTrend, silentBp, now);
        }
        public CoinState withSilent(long bp) { return new CoinState(id, price, trend, bp, updated); }
    }

    public record Order(long id, String owner, String name, Side side, String coin,
                        long qty, long limit, long locked, long created) {}

    /** value: dla BUY koszt, dla SELL przychód netto (po prowizji) - w groszach. */
    public record Trade(long id, long ts, String owner, String name, Side side, String coin,
                        long qty, long value, String source) {}

    public record NewsItem(long id, long ts, String type, String text) {}

    public record PricePoint(long ts, long price) {}

    public record Payout(long id, String owner, String name, long amount, String status, long created) {}

    public record Candle(long t, long o, long h, long l, long c) {}

    /** Wynik wykonania transakcji. */
    public record Fill(long tradeId, String owner, String name, Side side, String coin, long qty,
                       long value, long fee, long avgPrice, long priceBefore, long priceAfter) {}

    public record RankEntry(String name, long worth) {}
}
