package pl.kryptogielda;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kryptogielda.core.*;
import pl.kryptogielda.core.MarketService.HoldingView;
import pl.kryptogielda.core.MarketService.PortfolioView;
import pl.kryptogielda.core.MarketService.PriceChange;
import pl.kryptogielda.core.MarketService.Quote;
import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.store.JdbcStore;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MarketServiceTest {

    @TempDir Path tmp;
    JdbcStore store;
    MarketService m;
    final AtomicLong time = new AtomicLong(1_700_000_000_000L);

    static final String A = "uuid-a", B = "uuid-b";

    @BeforeEach
    void setUp() {
        store = new JdbcStore("jdbc:sqlite:" + tmp.resolve("t.db"), null, null);
        m = new MarketService(store, Settings.defaults(), time::get, Logger.getLogger("test"));
        m.init(List.of(
                new CoinDef("MineCoin", 10_000, "GOLD_NUGGET", 0.96, 1.04),
                new CoinDef("CreepDoge", 1_000, "GUNPOWDER", 0.85, 1.15)));
    }

    @AfterEach
    void tearDown() { store.close(); }

    long money(String s) { return Fixed.parseMoney(s); }
    long qty(String s) { return Fixed.parseQty(s); }
    long bal(String owner) { return m.account(owner).balance(); }

    // ------------------------------------------------------------------ Fixed

    @Test
    void parsing() {
        assertEquals(1250, Fixed.parseMoney("12.5"));
        assertEquals(1250, Fixed.parseMoney("12,50"));
        assertEquals(Fixed.INVALID, Fixed.parseMoney("12.505"));
        assertEquals(Fixed.INVALID, Fixed.parseMoney("-5"));
        assertEquals(Fixed.INVALID, Fixed.parseMoney("1e5"));
        assertEquals(Fixed.INVALID, Fixed.parseMoney("NaN"));
        assertEquals(Fixed.INVALID, Fixed.parseQty("0"));
        assertEquals(Fixed.INVALID, Fixed.parseQty("0.00001"));
        assertEquals(5000, Fixed.parseQty("0.5"));
        assertEquals(Fixed.INVALID, Fixed.parseQty("99999999999999999999"));
        assertEquals("0.5", Fixed.qty(5000));
        assertEquals("12", Fixed.qty(120000));
        assertEquals("1 234.50", Fixed.moneyPretty(123450));
    }

    // ------------------------------------------------------------------ kupno / sprzedaż

    @Test
    void buyAndSellBasics() {
        m.deposit(A, "Ala", money("10000"));
        Result<Fill> b = m.buyWithBalance(A, "Ala", "minecoin", qty("10"), MarketService.SRC_WEB);
        assertTrue(b.ok, b.toString());
        assertEquals("MineCoin", b.value.coin());
        assertEquals(money("10000") - b.value.value(), bal(A));
        assertEquals(qty("10"), m.holdingQty(A, "MineCoin"));
        assertTrue(m.price("MineCoin") > 10_000, "kupno podnosi cenę");

        Result<Fill> s = m.sellToBalance(A, "Ala", "MineCoin", qty("10"), MarketService.SRC_WEB);
        assertTrue(s.ok, s.toString());
        assertEquals(0, m.holdingQty(A, "MineCoin"));
        assertTrue(bal(A) < money("10000"), "cykl kup-sprzedaj musi kosztować");
    }

    @Test
    void roundTripNeverProfitable() {
        // dla różnych wielkości (od drobnych po gigantyczne, przekraczające limit wpływu 12%)
        String[] sizes = {"0.0001", "0.01", "1", "10", "100", "1000", "10000", "100000", "1000000"};
        for (String s : sizes) {
            tearDown(); setUp();
            m.deposit(A, "Ala", money("1000000000"));
            long before = bal(A);
            Result<Fill> b = m.buyWithBalance(A, "Ala", "MineCoin", qty(s), "WEB");
            if (!b.ok) continue; // zbyt mała transakcja
            Result<Fill> r = m.sellToBalance(A, "Ala", "MineCoin", qty(s), "WEB");
            if (!r.ok) continue;
            assertTrue(bal(A) <= before, "zysk z cyklu kup-sprzedaj dla ilości " + s + ": " + (bal(A) - before));
        }
    }

    @Test
    void pumpAndDumpWithTwoAccountsNoFreeMoney() {
        // A kupuje dużo (pompuje), B kupuje tanio wcześniej i sprzedaje po pompie - to normalny rynek,
        // ale A (pompujący) nie może zarobić na własnej pompie.
        m.deposit(A, "Ala", money("5000000"));
        long before = bal(A);
        for (int i = 0; i < 20; i++) {
            m.buyWithBalance(A, "Ala", "MineCoin", qty("500"), "WEB");
        }
        long h = m.holdingQty(A, "MineCoin");
        m.sellToBalance(A, "Ala", "MineCoin", h, "WEB");
        assertTrue(bal(A) < before);
    }

    @Test
    void slicingBuysThenDumpingNeverProfits() {
        // dokładnie scenariusz, który dawał zysk w starym silniku: wiele kupien, jedna wielka sprzedaż
        m.deposit(A, "Ala", money("100000000"));
        long before = bal(A);
        for (int i = 0; i < 40; i++) assertTrue(m.buyWithBalance(A, "Ala", "MineCoin", qty("300"), "WEB").ok);
        long held = m.holdingQty(A, "MineCoin");
        assertTrue(m.sellToBalance(A, "Ala", "MineCoin", held, "WEB").ok);
        assertTrue(bal(A) < before, "zysk: " + (bal(A) - before));
    }

    @Test
    void buyBigThenSellInTinySlicesNeverProfits() {
        // odwrotnie: jedno duże kupno, potem sprzedaż w drobnych kawałkach (zaokrąglenia ceny nie mogą pomagać)
        m.deposit(A, "Ala", money("100000000"));
        long before = bal(A);
        assertTrue(m.buyWithBalance(A, "Ala", "CreepDoge", qty("20000"), "WEB").ok);
        long held = m.holdingQty(A, "CreepDoge");
        long slice = qty("1");
        long sold = 0;
        while (sold + slice <= held) {
            Result<Fill> r = m.sellToBalance(A, "Ala", "CreepDoge", slice, "WEB");
            if (!r.ok) break; // zbyt drobna sprzedaż tuż przy końcu
            sold += slice;
        }
        long rest = m.holdingQty(A, "CreepDoge");
        if (rest > 0) m.sellToBalance(A, "Ala", "CreepDoge", rest, "WEB");
        assertTrue(bal(A) < before, "zysk: " + (bal(A) - before));
    }

    @Test
    void tinyTradesCannotBeFarmed() {
        // tysiące mikro-transakcji w obie strony nie mogą wygenerować pieniędzy
        m.deposit(A, "Ala", money("1000"));
        long before = bal(A);
        for (int i = 0; i < 300; i++) {
            m.buyWithBalance(A, "Ala", "CreepDoge", qty("0.1"), "WEB");
            long h = m.holdingQty(A, "CreepDoge");
            if (h > 0) m.sellToBalance(A, "Ala", "CreepDoge", h, "WEB");
        }
        assertTrue(bal(A) <= before);
    }

    @Test
    void curveIsPathIndependent() {
        // jedno kupno 100 szt. ma kosztować prawie tyle samo co 100 kupien po 1 szt. i dawać tę samą cenę końcową
        m.deposit(A, "Ala", money("1000000"));
        m.deposit(B, "Bob", money("1000000"));
        Result<Fill> big = m.buyWithBalance(A, "Ala", "MineCoin", qty("100"), "WEB");
        long priceBig = m.price("MineCoin");
        m.resetMarket(false);
        long spent = 0;
        for (int i = 0; i < 100; i++) spent += m.buyWithBalance(B, "Bob", "MineCoin", qty("1"), "WEB").value.value();
        long priceSliced = m.price("MineCoin");
        assertEquals(priceBig, priceSliced, 1);
        assertEquals(big.value.value(), spent, 150, "różnica tylko od zaokrągleń w górę do grosza");
    }

    @Test
    void oversizedBuyRejected() {
        m.deposit(A, "Ala", Fixed.MAX_MONEY);
        long cap = m.maxSingleBuyQty("MineCoin");
        assertTrue(cap > 0);
        assertEquals("TOO_LARGE", m.buyWithBalance(A, "Ala", "MineCoin", cap + 10_000, "WEB").code);
        assertTrue(m.buyWithBalance(A, "Ala", "MineCoin", cap, "WEB").ok);
        // sprzedaż dowolnie dużej ilości jest dozwolona i ograniczona tylko płynnością rynku
        Result<Fill> dump = m.sellToBalance(A, "Ala", "MineCoin", m.holdingQty(A, "MineCoin"), "WEB");
        assertTrue(dump.ok);
        assertTrue(m.price("MineCoin") >= Settings.defaults().minPrice());
    }

    @Test
    void floorPriceHandledOnHugeSell() {
        m.deposit(A, "Ala", Fixed.MAX_MONEY);
        m.adminHolding("Ala", "MineCoin", MarketService.Op.SET, 0); // brak konta -> NO_ACCOUNT jest ok
        m.ensureAccount(A, "Ala");
        assertTrue(m.adminHolding("Ala", "MineCoin", MarketService.Op.SET, qty("900000000")).ok);
        long before = bal(A);
        Result<Fill> r = m.sellToBalance(A, "Ala", "MineCoin", qty("900000000"), "WEB");
        assertTrue(r.ok, r.toString());
        assertEquals(Settings.defaults().minPrice(), m.price("MineCoin"));
        assertTrue(bal(A) > before);
        // wypłata nie może przekroczyć sensownej płynności (Q * cena), a nie 900 mln * cena
        assertTrue(r.value.value() < Fixed.value(qty("900000000"), 10_000, java.math.RoundingMode.DOWN) / 100);
    }

    @Test
    void insufficientFundsAndHoldings() {
        m.deposit(A, "Ala", money("50"));
        Result<Fill> b = m.buyWithBalance(A, "Ala", "MineCoin", qty("1"), "WEB");
        assertFalse(b.ok);
        assertEquals("INSUFFICIENT_FUNDS", b.code);
        assertEquals(money("50"), bal(A));
        assertEquals(0, m.holdingQty(A, "MineCoin"));

        Result<Fill> s = m.sellToBalance(A, "Ala", "MineCoin", qty("1"), "WEB");
        assertFalse(s.ok);
        assertEquals("INSUFFICIENT_HOLDINGS", s.code);
    }

    @Test
    void invalidInputs() {
        m.deposit(A, "Ala", money("1000"));
        assertEquals("NO_COIN", m.buyWithBalance(A, "Ala", "Nope", 10000, "WEB").code);
        assertEquals("INVALID_AMOUNT", m.buyWithBalance(A, "Ala", "MineCoin", 0, "WEB").code);
        assertEquals("INVALID_AMOUNT", m.buyWithBalance(A, "Ala", "MineCoin", -5, "WEB").code);
        assertEquals("INVALID_AMOUNT", m.buyWithBalance(A, "Ala", "MineCoin", Long.MAX_VALUE, "WEB").code);
        assertEquals("INVALID_AMOUNT", m.deposit(A, "Ala", -1).code);
        assertEquals("INVALID_AMOUNT", m.deposit(A, "Ala", 0).code);
        assertEquals(money("1000"), bal(A));
    }

    @Test
    void costBasisTracksAverage() {
        m.deposit(A, "Ala", money("1000000"));
        m.buyWithBalance(A, "Ala", "MineCoin", qty("10"), "WEB");
        m.buyWithBalance(A, "Ala", "MineCoin", qty("10"), "WEB");
        PortfolioView p = m.portfolio(A);
        HoldingView hv = p.holdings().get(0);
        assertEquals(qty("20"), hv.qty());
        assertTrue(hv.cost() > 0);
        m.sellToBalance(A, "Ala", "MineCoin", qty("10"), "WEB");
        HoldingView after = m.portfolio(A).holdings().get(0);
        assertEquals(qty("10"), after.qty());
        assertEquals(hv.cost() / 2, after.cost(), 1);
        m.sellToBalance(A, "Ala", "MineCoin", qty("10"), "WEB");
        assertTrue(m.portfolio(A).holdings().isEmpty());
    }

    // ------------------------------------------------------------------ współbieżność (dawne dziury z PHP)

    @Test
    void parallelSellsCannotSellMoreThanOwned() throws Exception {
        m.deposit(A, "Ala", money("1000000"));
        m.buyWithBalance(A, "Ala", "MineCoin", qty("100"), "WEB");
        long before = bal(A);
        int threads = 40;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(ex.submit(() -> {
                go.await();
                if (m.sellToBalance(A, "Ala", "MineCoin", qty("100"), "WEB").ok) okCount.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(1, okCount.get(), "dokładnie jedna sprzedaż całości powinna się udać");
        assertEquals(0, m.holdingQty(A, "MineCoin"));
        assertTrue(bal(A) > before);
    }

    @Test
    void parallelWithdrawsCannotExceedBalance() throws Exception {
        m.deposit(A, "Ala", money("100"));
        int threads = 40;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(ex.submit(() -> {
                go.await();
                if (m.requestWithdraw(A, money("100")).ok) okCount.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(1, okCount.get());
        assertEquals(0, bal(A));
        assertEquals(1, m.payouts(MarketService.PAYOUT_PENDING).size());
    }

    @Test
    void parallelBuysCannotOverspend() throws Exception {
        m.deposit(A, "Ala", money("5000"));
        int threads = 30;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result<Fill>>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(ex.submit(() -> {
                go.await();
                return m.buyWithBalance(A, "Ala", "CreepDoge", qty("100"), "WEB");
            }));
        }
        go.countDown();
        long spent = 0;
        for (Future<Result<Fill>> f : fs) { Result<Fill> r = f.get(30, TimeUnit.SECONDS); if (r.ok) spent += r.value.value(); }
        ex.shutdown();
        assertEquals(money("5000") - spent, bal(A));
        assertTrue(bal(A) >= 0);
    }

    // ------------------------------------------------------------------ wypłaty

    @Test
    void payoutLifecycle() {
        m.deposit(A, "Ala", money("500"));
        Payout p = m.requestWithdraw(A, money("200")).value;
        assertEquals(money("300"), bal(A));
        assertTrue(m.markPayoutSending(p.id()));
        assertFalse(m.markPayoutSending(p.id()), "nie można rozpocząć dwa razy");
        assertTrue(m.payouts(MarketService.PAYOUT_PENDING).isEmpty());
        assertEquals(1, m.payouts(MarketService.PAYOUT_SENDING).size());
        m.failPayout(p.id());
        assertEquals(money("500"), bal(A), "nieudana wypłata zwraca środki");
        assertTrue(m.payouts(MarketService.PAYOUT_SENDING).isEmpty());

        Payout p2 = m.requestWithdraw(A, money("100")).value;
        m.markPayoutSending(p2.id());
        m.completePayout(p2.id());
        assertEquals(money("400"), bal(A));
        assertTrue(m.payouts(MarketService.PAYOUT_SENDING).isEmpty());
    }

    @Test
    void withdrawValidation() {
        m.deposit(A, "Ala", money("50"));
        assertEquals("INVALID_AMOUNT", m.requestWithdraw(A, 50).code);
        assertEquals("INSUFFICIENT_FUNDS", m.requestWithdraw(A, money("51")).code);
        assertEquals("INSUFFICIENT_FUNDS", m.requestWithdraw("nobody", money("5")).code);
    }

    // ------------------------------------------------------------------ zewnętrzne płatności (Vault)

    @Test
    void externalBuyAndSell() {
        Quote q = m.quoteBuy("MineCoin", qty("5")).value;
        Result<Fill> b = m.buyPaidExternally(A, "Ala", "MineCoin", qty("5"), q.value(), "GAME");
        assertTrue(b.ok);
        assertEquals(q.value(), b.value.value());
        assertEquals(0, bal(A), "saldo giełdowe nietknięte");
        assertEquals(qty("5"), m.holdingQty(A, "MineCoin"));

        // cena wzrosła -> odrzucenie
        m.setPrice("MineCoin", 20_000);
        Result<Fill> b2 = m.buyPaidExternally(A, "Ala", "MineCoin", qty("5"), q.value(), "GAME");
        assertFalse(b2.ok);
        assertEquals("PRICE_MOVED", b2.code);

        Result<Fill> s = m.sellForPayout(A, "Ala", "MineCoin", qty("5"), "GAME");
        assertTrue(s.ok);
        assertEquals(0, bal(A), "przychód wypłaca wywołujący");
        assertTrue(s.value.value() > 0);
    }

    // ------------------------------------------------------------------ zlecenia limit

    @Test
    void buyLimitFillsWhenPriceDrops() {
        m.deposit(A, "Ala", money("100000"));
        long limit = 8_000; // 80.00
        Result<Order> o = m.placeLimit(A, "Ala", Side.BUY, "MineCoin", qty("10"), limit);
        assertTrue(o.ok, o.toString());
        assertTrue(bal(A) < money("100000"), "środki zablokowane");
        assertEquals(0, m.processOrders(), "cena 100 > limit 80 -> brak realizacji");

        m.setPrice("MineCoin", 7_500);
        assertEquals(1, m.processOrders());
        assertEquals(qty("10"), m.holdingQty(A, "MineCoin"));
        assertTrue(m.orders(A).isEmpty());
        // koszt <= zablokowane, reszta wróciła
        long spent = money("100000") - bal(A);
        assertTrue(spent <= Fixed.value(qty("10"), limit, java.math.RoundingMode.UP) * 11 / 10);
        assertTrue(spent > 0);
    }

    @Test
    void sellLimitFillsWhenPriceRises() {
        m.deposit(A, "Ala", money("100000"));
        m.buyWithBalance(A, "Ala", "MineCoin", qty("10"), "WEB");
        long afterBuy = bal(A);
        long cost = m.portfolio(A).holdings().get(0).cost();
        Order o = m.placeLimit(A, "Ala", Side.SELL, "MineCoin", qty("10"), 50_000).value;
        assertEquals(0, m.holdingQty(A, "MineCoin"), "aktywa zablokowane w zleceniu");
        assertEquals(cost, o.locked(), "koszt nabycia zapamiętany w zleceniu");
        assertEquals(0, m.processOrders());
        m.setPrice("MineCoin", 60_000);
        assertEquals(1, m.processOrders());
        assertTrue(bal(A) > afterBuy);
    }

    @Test
    void cancelOrdersRestoreEverything() {
        m.deposit(A, "Ala", money("100000"));
        long b0 = bal(A);
        Order buy = m.placeLimit(A, "Ala", Side.BUY, "MineCoin", qty("10"), 5_000).value;
        assertTrue(bal(A) < b0);
        assertTrue(m.cancelOrder(A, buy.id()).ok);
        assertEquals(b0, bal(A));
        assertFalse(m.cancelOrder(A, buy.id()).ok, "podwójne anulowanie");

        m.buyWithBalance(A, "Ala", "MineCoin", qty("4"), "WEB");
        Holding before = store.inTx(tx -> tx.holding(A, "MineCoin"));
        Order sell = m.placeLimit(A, "Ala", Side.SELL, "MineCoin", qty("4"), 99_000).value;
        assertEquals(0, m.holdingQty(A, "MineCoin"));
        assertTrue(m.cancelOrder(A, sell.id()).ok);
        Holding after = store.inTx(tx -> tx.holding(A, "MineCoin"));
        assertEquals(before, after);
    }

    @Test
    void cannotCancelSomeoneElsesOrder() {
        m.deposit(A, "Ala", money("1000"));
        Order o = m.placeLimit(A, "Ala", Side.BUY, "MineCoin", qty("1"), 5_000).value;
        assertEquals("NO_ORDER", m.cancelOrder(B, o.id()).code);
        assertEquals(1, m.orders(A).size());
    }

    @Test
    void orderLimitCount() {
        m.deposit(A, "Ala", money("1000000"));
        int max = Settings.defaults().maxOrdersPerPlayer();
        for (int i = 0; i < max; i++) assertTrue(m.placeLimit(A, "Ala", Side.BUY, "CreepDoge", qty("1"), 500).ok);
        assertEquals("TOO_MANY_ORDERS", m.placeLimit(A, "Ala", Side.BUY, "CreepDoge", qty("1"), 500).code);
    }

    // ------------------------------------------------------------------ ceny, wykresy, ranking

    @Test
    void multiplierAndSilentTrend() {
        assertTrue(m.setSilentTrend("MineCoin", 40).ok);
        PriceChange c = m.applyMultiplier("MineCoin", 1.0, true).value;
        assertEquals(14_000, c.newPrice(), "cichy trend +40% zużyty");
        PriceChange c2 = m.applyMultiplier("MineCoin", 1.0, true).value;
        assertEquals(c2.oldPrice(), c2.newPrice(), "trend jednorazowy");
        assertEquals("NO_COIN", m.applyMultiplier("X", 1, true).code);
        // podłoga ceny
        m.applyMultiplier("MineCoin", 0.0001, false);
        assertEquals(Settings.defaults().minPrice(), m.price("MineCoin"));
        assertEquals("INVALID_AMOUNT", m.setSilentTrend("MineCoin", Double.NaN).code);
    }

    @Test
    void candlesAggregate() {
        long t0 = time.get();
        time.set(t0 + 10_000); m.setPrice("MineCoin", 10_500);
        time.set(t0 + 20_000); m.setPrice("MineCoin", 9_800);
        time.set(t0 + 30_000); m.setPrice("MineCoin", 10_200);
        time.set(t0 + 400_000); m.setPrice("MineCoin", 11_000);
        List<Candle> cs = m.candles("MineCoin", 300, 100);
        assertTrue(cs.size() >= 2);
        Candle first = cs.get(0);
        assertEquals(10_000, first.o());
        assertEquals(10_500, first.h());
        assertEquals(9_800, first.l());
        assertEquals(10_200, first.c());
        Candle last = cs.get(cs.size() - 1);
        assertEquals(11_000, last.c());
        assertEquals(first.c(), cs.get(1).o(), "świece są ciągłe");
    }

    @Test
    void rankingCountsEverythingAndHidesBots() {
        m.deposit(A, "Ala", money("1000"));
        m.deposit(B, "Bob", money("5000"));
        m.buyWithBalance(B, "Bob", "MineCoin", qty("10"), "WEB");
        m.placeLimit(A, "Ala", Side.BUY, "MineCoin", qty("1"), 5_000);
        m.botTrade("Wieloryb", "MineCoin", Side.BUY, money("100000"), 0, 1.02);
        List<RankEntry> r = m.ranking(10, false);
        assertEquals(2, r.size());
        assertEquals("Bob", r.get(0).name());
        // pieniądze zablokowane w zleceniu Ali nadal liczą się do kapitału
        assertEquals("Ala", r.get(1).name());
        assertTrue(r.get(1).worth() >= money("999"));
        assertEquals(3, m.ranking(10, true).size());
    }

    @Test
    void maxBuyQtyIsAffordableAndMaximal() {
        long budget = money("12345.67");
        long q = m.maxBuyQty("MineCoin", budget);
        assertTrue(q > 0);
        assertTrue(m.quoteBuy("MineCoin", q).value.value() <= budget);
        assertTrue(m.quoteBuy("MineCoin", q + 1).value.value() > budget);
        assertEquals(0, m.maxBuyQty("MineCoin", 0));
        assertEquals(0, m.maxBuyQty("Nope", budget));
    }

    // ------------------------------------------------------------------ boty, admin

    @Test
    void botTradesMoveMarketButNotRealBalances() {
        long p0 = m.price("MineCoin");
        Result<Fill> b = m.botTrade("Wieloryb", "MineCoin", Side.BUY, money("50000"), 0, 1.05);
        assertTrue(b.ok, b.toString());
        assertTrue(m.price("MineCoin") > p0);
        long q = m.holdingQty(MarketService.botOwner("Wieloryb"), "MineCoin");
        assertTrue(q > 0);
        Result<Fill> s = m.botTrade("Wieloryb", "MineCoin", Side.SELL, 0, 0.5, 0.95);
        assertTrue(s.ok);
        assertEquals(q - (long) Math.floor(q * 0.5), m.holdingQty(MarketService.botOwner("Wieloryb"), "MineCoin"));
        assertFalse(m.botTrade("Pusty", "MineCoin", Side.SELL, 0, 1, 0.9).ok);
        assertEquals(2, m.recentTrades(10).size());
    }

    @Test
    void adminEdits() {
        m.deposit(A, "Ala", money("100"));
        assertEquals(money("150"), m.adminBalance("ala", MarketService.Op.ADD, money("50")).value);
        assertEquals(money("10"), m.adminBalance("ALA", MarketService.Op.SET, money("10")).value);
        assertEquals(0, m.adminBalance("Ala", MarketService.Op.SUB, money("999")).value);
        assertEquals("NO_ACCOUNT", m.adminBalance("Nikt", MarketService.Op.ADD, 1).code);
        assertEquals(qty("3"), m.adminHolding("Ala", "MineCoin", MarketService.Op.ADD, qty("3")).value);
        assertEquals(qty("1"), m.adminHolding("Ala", "MineCoin", MarketService.Op.SUB, qty("2")).value);
        assertEquals("INVALID_OP", m.adminBalance("Ala", null, 1).code);
    }

    @Test
    void resetKeepsBalancesUnlessWiping() {
        m.deposit(A, "Ala", money("1000"));
        m.buyWithBalance(A, "Ala", "MineCoin", qty("2"), "WEB");
        m.placeLimit(A, "Ala", Side.BUY, "MineCoin", qty("1"), 5_000);
        m.addNews("TEST", "x");
        long balBefore = bal(A);
        m.resetMarket(false);
        assertEquals(10_000, m.price("MineCoin"));
        assertTrue(m.recentTrades(5).isEmpty());
        assertTrue(m.news(5).isEmpty());
        assertTrue(m.orders(A).isEmpty());
        assertTrue(bal(A) > balBefore, "zlecenia zwrócone");
        assertEquals(qty("2"), m.holdingQty(A, "MineCoin"));

        m.resetMarket(true);
        assertEquals(0, bal(A));
        assertEquals(0, m.holdingQty(A, "MineCoin"));
    }

    @Test
    void listenersAreNotifiedAfterCommit() {
        List<String> events = new ArrayList<>();
        m.addListener(new MarketService.Listener() {
            @Override public void onTrade(Fill f, String s) { events.add("trade:" + f.side() + ":" + s); }
            @Override public void onPriceChange(String c, long o, long n) { events.add("price:" + c); }
            @Override public void onOrderFilled(Order o, Fill f) { events.add("order"); }
        });
        m.deposit(A, "Ala", money("100000"));
        m.buyWithBalance(A, "Ala", "MineCoin", qty("1"), "WEB");
        assertTrue(events.contains("trade:BUY:WEB"));
        assertTrue(events.contains("price:MineCoin"));
        events.clear();
        m.buyWithBalance(A, "Ala", "MineCoin", qty("100000"), "WEB"); // porażka: brak środków
        assertTrue(events.isEmpty(), "nieudana transakcja nie generuje zdarzeń");
    }

    @Test
    void persistenceAcrossRestart() {
        m.deposit(A, "Ala", money("777"));
        m.buyWithBalance(A, "Ala", "MineCoin", qty("1"), "WEB");
        long price = m.price("MineCoin");
        long balance = bal(A);
        store.close();
        store = new JdbcStore("jdbc:sqlite:" + tmp.resolve("t.db"), null, null);
        m = new MarketService(store, Settings.defaults(), time::get, Logger.getLogger("test"));
        m.init(List.of(new CoinDef("MineCoin", 10_000, "GOLD_NUGGET", 0.96, 1.04)));
        assertEquals(price, m.price("MineCoin"));
        assertEquals(balance, bal(A));
        assertEquals(qty("1"), m.holdingQty(A, "MineCoin"));
    }

    @Test
    void pruneKeepsLatestPoint() {
        long t0 = time.get();
        for (int i = 1; i <= 5; i++) { time.set(t0 + i * 1000L); m.setPrice("MineCoin", 10_000 + i); }
        time.set(t0 + 40L * 24 * 3600 * 1000);
        m.prune();
        List<Candle> cs = m.candles("MineCoin", 3600 * 24, 1000);
        assertFalse(cs.isEmpty(), "ostatni punkt ceny zachowany");
    }

    @Test
    void stats24hTrackHighLowAndOpenWithoutLoadingHistory() {
        long open = m.price("MineCoin");
        time.addAndGet(60_000); m.applyMultiplier("MineCoin", 1.20, false);
        time.addAndGet(60_000); m.applyMultiplier("MineCoin", 0.50, false);
        MarketService.CoinView v = m.coins().stream().filter(x -> x.id().equals("MineCoin")).findFirst().get();
        assertEquals(open, v.open24h());
        assertEquals((double) Math.round(open * 1.2), (double) v.high24h(), 1.0);
        assertEquals((double) Math.round(open * 0.6), (double) v.low24h(), 1.0);
        assertEquals((double) Math.round(open * 0.6), (double) v.price(), 1.0);
        // punkty starsze niż 24 h nie wliczają się
        time.addAndGet(30L * 3600 * 1000);
        m.applyMultiplier("MineCoin", 1.01, false);
        MarketService.CoinView w = m.coins().stream().filter(x -> x.id().equals("MineCoin")).findFirst().get();
        assertEquals(w.price(), w.high24h(), "po 30 h stary szczyt wypada z okna");
    }

    @Test
    void oldPriceHistoryIsThinnedButCandlesSurvive() {
        long start = time.get();
        // 3 dni punktów co 20 s
        for (int i = 0; i < 3 * 24 * 180; i++) {
            time.addAndGet(20_000);
            m.applyMultiplier("MineCoin", i % 2 == 0 ? 1.002 : 0.9985, false);
        }
        int before = store.inTx(tx -> tx.prices("MineCoin", 0).size());
        long lastPrice = m.price("MineCoin");
        m.prune();
        int after = store.inTx(tx -> tx.prices("MineCoin", 0).size());
        long cut = time.get() - 48L * 3600 * 1000;
        int newPart = store.inTx(tx -> tx.prices("MineCoin", cut).size());   // ostatnie 48 h nie są ruszane
        int oldBefore = before - newPart;
        int oldAfter = after - newPart;
        assertTrue(oldBefore > 3000, "test zakłada ok. doby starej historii: " + oldBefore);
        assertTrue(oldAfter < oldBefore * 0.4, "stara część powinna się skurczyć: " + oldBefore + " -> " + oldAfter);
        assertEquals(lastPrice, m.price("MineCoin"));
        // świece z ostatnich 48 h są nietknięte (pełna rozdzielczość), starsze dalej istnieją
        long recent = store.inTx(tx -> tx.prices("MineCoin", time.get() - 47L * 3600 * 1000).size());
        assertTrue(recent > 47 * 170, "ostatnie 47 h bez zmian: " + recent);
        List<Candle> old = m.candles("MineCoin", 3600, 100);
        assertFalse(old.isEmpty());
        // idempotencja
        m.prune();
        assertEquals(after, (int) store.inTx(tx -> tx.prices("MineCoin", 0).size()));
    }
}
