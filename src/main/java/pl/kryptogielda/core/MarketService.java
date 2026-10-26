package pl.kryptogielda.core;

import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.core.Store.Tx;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cała logika rynku w jednym miejscu. Każda operacja zmieniająca stan wykonuje się w JEDNEJ
 * transakcji bazy danych, szeregowanej przez {@link Store}, więc nie ma wyścigów typu
 * "podwójna sprzedaż" czy "podwójna wypłata". Cenę zawsze liczy serwer - klient nigdy jej nie podaje.
 *
 * <p>Model cen: krzywa wykładnicza niezależna od ścieżki. Cena po kupnie {@code q} sztuk to
 * {@code P * exp(q/Q)}, a zapłacona kwota to całka po ścieżce ceny: {@code P*Q*(exp(q/Q)-1)}.
 * Sprzedaż jest dokładnym odwróceniem. Skutki: kupno i natychmiastowa sprzedaż tej samej ilości
 * kosztuje dokładnie tyle, ile prowizja; dzielenie zleceń na kawałki niczego nie zmienia, więc nie da
 * się zarobić na "pompowaniu własnej ceny". Ceny wewnątrz są trzymane z dokładnością 1e-6 waluty,
 * żeby zaokrąglenia do groszy nie dało się wykorzystać przy drobnych transakcjach.
 */
public final class MarketService {

    public static final String UP = "Wzrost (Hossa)";
    public static final String DOWN = "Spadek (Bessa)";
    public static final String FLAT = "Stabilnie";

    public static final String SRC_WEB = "WEB";
    public static final String SRC_GAME = "GAME";
    public static final String SRC_LIMIT = "LIMIT";
    public static final String SRC_BOT = "BOT";

    public static final String PAYOUT_PENDING = "PENDING";
    public static final String PAYOUT_SENDING = "SENDING";

    private static final BigDecimal TEN_K = BigDecimal.valueOf(10_000);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal BP = BigDecimal.valueOf(10_000);

    // ------------------------------------------------------------------ typy pomocnicze

    public interface Listener {
        default void onTrade(Fill fill, String source) {}
        default void onPriceChange(String coin, long oldPrice, long newPrice) {}
        default void onOrderFilled(Order order, Fill fill) {}
    }

    public record CoinView(String id, String icon, long price, long sellPrice, String trend,
                           long open24h, long high24h, long low24h) {}

    public record HoldingView(String coin, long qty, long cost, long price, long value) {}

    public record PortfolioView(String owner, String name, long balance, List<HoldingView> holdings,
                                List<Order> orders, long netWorth) {}

    public record Quote(long qty, long value, long fee, long net, long avgPrice, long priceBefore, long priceAfter) {}

    /** Wynik wyliczenia transakcji + nowa cena wewnętrzna. */
    private record Calc(Quote q, long newMicro) {}

    public record PriceChange(String coin, long oldPrice, long newPrice) {}

    private enum Funding { BALANCE, ESCROW, EXTERNAL }

    // ------------------------------------------------------------------ pola

    private final Store store;
    private final Settings settings;
    private final LongSupplier clock;
    private final Logger log;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, CoinDef> defs = new LinkedHashMap<>();

    private volatile List<CoinView> cachedCoins;
    private volatile long cachedAt;
    private static final long CACHE_MS = 1000;

    public MarketService(Store store, Settings settings, LongSupplier clock, Logger log) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.log = log;
    }

    public Settings settings() { return settings; }

    public void addListener(Listener l) { listeners.add(l); }

    public long now() { return clock.getAsLong(); }

    // ------------------------------------------------------------------ transakcje + zdarzenia po zatwierdzeniu

    private interface Body<T> {
        T run(Tx tx, List<Runnable> post) throws Exception;
    }

    private <T> T run(Body<T> body) {
        List<Runnable> post = new ArrayList<>();
        T result = store.inTx(tx -> body.run(tx, post));
        cachedCoins = null;
        for (Runnable r : post) {
            try {
                r.run();
            } catch (Throwable t) {
                log.log(Level.WARNING, "Error in a market listener", t);
            }
        }
        return result;
    }

    private static <T> Result<T> fail(String code, String msg) { return Result.fail(code, msg); }

    // ------------------------------------------------------------------ inicjalizacja

    public static final java.util.regex.Pattern COIN_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_]{2,24}");
    private static final java.util.regex.Pattern ICON_ID = java.util.regex.Pattern.compile("[A-Z0-9_]{1,40}");

    private volatile Set<String> customIds = Set.of();
    private volatile List<CustomEvent> customEvents = List.of();

    /**
     * Ustawia listę kryptowalut z configu, dokłada kryptowaluty i eventy dodane przez admina (z bazy)
     * i tworzy brakujące wpisy z cenami startowymi. Przy zdublowanej nazwie wygrywa config.yml.
     */
    public void init(List<CoinDef> coinDefs) {
        Map<String, CoinDef> m = new LinkedHashMap<>();
        for (CoinDef d : coinDefs) m.put(d.id(), d);
        Set<String> custom = new java.util.LinkedHashSet<>();
        List<CustomEvent> evs = run((tx, post) -> {
            long t = now();
            for (CoinDef d : tx.customCoins()) {
                if (m.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(d.id()))) continue;
                m.put(d.id(), d);
                custom.add(d.id());
            }
            for (CoinDef d : m.values()) {
                if (tx.coin(d.id()) == null) {
                    long micro = Fixed.microOf(Math.max(settings.minPrice(), d.startPrice()));
                    tx.saveCoin(new CoinState(d.id(), micro, FLAT, 0, t));
                    tx.insertPrice(d.id(), t, micro);
                }
            }
            return tx.customEvents();
        });
        this.defs = m;
        this.customIds = custom;
        this.customEvents = evs;
    }

    // ------------------------------------------------------------------ własne kryptowaluty (admin)

    public boolean isCustomCoin(String id) { return customIds.contains(id); }

    private Result<CoinDef> validateCoin(CoinDef d) {
        if (d.id() == null || !COIN_ID.matcher(d.id()).matches())
            return fail("BAD_NAME", "Nazwa: 2-24 znaki, tylko litery, cyfry i _.");
        if (d.startPrice() < settings.minPrice() || d.startPrice() > 100_000_000_00L)
            return fail("INVALID_PRICE", "Cena startowa poza dozwolonym zakresem.");
        if (!(d.volMin() >= 0.5 && d.volMin() <= d.volMax() && d.volMax() <= 2.0))
            return fail("BAD_VOLATILITY", "Zmienność: 0.5 <= min <= max <= 2.0 (np. 0.96 i 1.04).");
        if (!(d.liquidity() >= 0 && d.liquidity() <= 1e9))
            return fail("BAD_LIQUIDITY", "Płynność: od 0 do 1 000 000 000.");
        String icon = d.icon() == null ? "" : d.icon().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ICON_ID.matcher(icon).matches())
            return fail("BAD_ICON", "Ikona: nazwa materiału Minecraft, np. GOLD_NUGGET.");
        return Result.ok(new CoinDef(d.id(), d.startPrice(), icon, d.volMin(), d.volMax(), d.liquidity()));
    }

    /** Dodaje nową kryptowalutę w trakcie działania serwera (zapis w bazie). */
    public synchronized Result<CoinDef> addCustomCoin(CoinDef in) {
        Result<CoinDef> v = validateCoin(in);
        if (!v.ok) return v;
        CoinDef d = v.value;
        if (canon(d.id()) != null) return fail("EXISTS", "Kryptowaluta o tej nazwie już istnieje.");
        run((tx, post) -> {
            long t = now();
            tx.saveCustomCoin(d);
            tx.deleteCoinData(d.id());
            long micro = Fixed.microOf(Math.max(settings.minPrice(), d.startPrice()));
            tx.saveCoin(new CoinState(d.id(), micro, FLAT, 0, t));
            tx.insertPrice(d.id(), t, micro);
            return null;
        });
        Map<String, CoinDef> m = new LinkedHashMap<>(defs);
        m.put(d.id(), d);
        Set<String> c = new java.util.LinkedHashSet<>(customIds);
        c.add(d.id());
        this.defs = m;
        this.customIds = c;
        cachedCoins = null;
        return Result.ok(d);
    }

    /** Zmienia parametry kryptowaluty dodanej z panelu (aktualna cena rynkowa zostaje bez zmian). */
    public synchronized Result<CoinDef> updateCustomCoin(CoinDef in) {
        String id = canon(in.id());
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (!customIds.contains(id)) return fail("NOT_CUSTOM", "Tę kryptowalutę zdefiniowano w config.yml - edytuj ją tam.");
        Result<CoinDef> v = validateCoin(new CoinDef(id, in.startPrice(), in.icon(), in.volMin(), in.volMax(), in.liquidity()));
        if (!v.ok) return v;
        CoinDef d = v.value;
        run((tx, post) -> { tx.saveCustomCoin(d); return null; });
        Map<String, CoinDef> m = new LinkedHashMap<>(defs);
        m.put(id, d);
        this.defs = m;
        cachedCoins = null;
        return Result.ok(d);
    }

    /**
     * Usuwa kryptowalutę dodaną z panelu. Odmawia, jeśli jakikolwiek gracz ma ją w portfelu lub zleceniu
     * (pozycje botów są kasowane).
     */
    public synchronized Result<Boolean> removeCustomCoin(String name) {
        String id = canon(name);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (!customIds.contains(id)) return fail("NOT_CUSTOM", "Tę kryptowalutę zdefiniowano w config.yml - usuń ją stamtąd.");
        Result<Boolean> r = run((tx, post) -> {
            for (Holding h : tx.allHoldings()) {
                if (h.coin().equals(id) && h.qty() > 0 && !h.owner().startsWith("bot:"))
                    return fail("IN_USE", "Gracze mają tę kryptowalutę w portfelach - nie można jej usunąć.");
            }
            for (Order o : tx.allOrders()) {
                if (o.coin().equals(id)) return fail("IN_USE", "Istnieją zlecenia limit na tę kryptowalutę - nie można jej usunąć.");
            }
            for (Holding h : tx.allHoldings()) {
                if (h.coin().equals(id)) tx.saveHolding(new Holding(h.owner(), h.coin(), 0, 0));
            }
            tx.deleteCustomCoin(id);
            tx.deleteCoinData(id);
            return Result.ok(true);
        });
        if (!r.ok) return r;
        Map<String, CoinDef> m = new LinkedHashMap<>(defs);
        m.remove(id);
        Set<String> c = new java.util.LinkedHashSet<>(customIds);
        c.remove(id);
        this.defs = m;
        this.customIds = c;
        cachedCoins = null;
        return r;
    }

    // ------------------------------------------------------------------ własne eventy (admin)

    public List<CustomEvent> customEvents() { return customEvents; }

    public synchronized Result<CustomEvent> addCustomEvent(String type, String text, String coin, double multiplier, int weight, String color) {
        type = type == null ? "" : type.trim();
        text = text == null ? "" : text.trim();
        String canon = coin == null || coin.isBlank() ? "" : canon(coin);
        if (canon == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (type.isEmpty() || type.length() > 40) return fail("BAD_EVENT", "Typ eventu: 1-40 znaków.");
        if (text.isEmpty() || text.length() > 300) return fail("BAD_EVENT", "Treść eventu: 1-300 znaków.");
        if (!canon.isEmpty() && !(multiplier >= 0.05 && multiplier <= 10.0)) return fail("BAD_MULTIPLIER", "Mnożnik ceny: od 0.05 do 10 (np. 1.30 = +30%, 0.70 = -30%).");
        if (weight < 0 || weight > 1000) return fail("BAD_EVENT", "Waga: 0-1000 (0 = tylko ręczne uruchamianie).");
        String col = color == null ? "" : color.trim();
        if (!col.isEmpty() && !col.matches("&[0-9a-fA-F]")) return fail("BAD_EVENT", "Kolor: kod &0-&f albo puste pole.");
        if (customEvents.size() >= 500) return fail("BAD_EVENT", "Za dużo własnych eventów (maks. 500).");
        final String ty = type, tx2 = text;
        final double mult = canon.isEmpty() ? 1.0 : multiplier;
        CustomEvent ev = run((tx, post) -> {
            CustomEvent e = new CustomEvent(tx.nextId("event"), ty, tx2, canon, mult, weight, col);
            tx.saveCustomEvent(e);
            return e;
        });
        List<CustomEvent> l = new ArrayList<>(customEvents);
        l.add(ev);
        customEvents = l;
        return Result.ok(ev);
    }

    public synchronized Result<Boolean> deleteCustomEvent(long id) {
        if (customEvents.stream().noneMatch(e -> e.id() == id)) return fail("NOT_FOUND", "Nie znaleziono eventu.");
        run((tx, post) -> { tx.deleteCustomEvent(id); return null; });
        List<CustomEvent> l = new ArrayList<>(customEvents);
        l.removeIf(e -> e.id() == id);
        customEvents = l;
        return Result.ok(true);
    }

    public List<CoinDef> coinDefs() { return new ArrayList<>(defs.values()); }

    /** Zwraca kanoniczną nazwę kryptowaluty (bez względu na wielkość liter) lub null. */
    public String canon(String name) {
        if (name == null) return null;
        String t = name.trim();
        for (String id : defs.keySet()) if (id.equalsIgnoreCase(t)) return id;
        return null;
    }

    // ------------------------------------------------------------------ matematyka cen

    private long minMicro() { return Fixed.microOf(settings.minPrice()); }

    /** Głębokość rynku Q: liczba sztuk, przy której cena zmienia się e-krotnie (~ 100 x sztuk na 1%). */
    private double depthOf(String coinId) {
        CoinDef d = defs.get(coinId);
        double perPercent = d.liquidity() > 0 ? d.liquidity()
                : (double) settings.impactValueForOnePercent() / Math.max(1, d.startPrice());
        return Math.max(1e-4, perPercent) * 100.0;
    }

    private double maxX() { return Math.log1p(settings.maxBuyMove()); }

    /** Największa ilość (1/10000) kupowana jednorazowo. */
    public long maxSingleBuyQty(String coin) {
        String id = canon(coin);
        if (id == null) return 0;
        return Math.min(Fixed.MAX_QTY, (long) Math.floor(depthOf(id) * maxX() * Fixed.QTY_SCALE));
    }

    private Result<Calc> calcBuy(String coin, long micro, long qty) {
        double q = depthOf(coin);
        double qc = qty / (double) Fixed.QTY_SCALE;
        double x = qc / q;
        if (x > maxX() + 1e-12) {
            return fail("TOO_LARGE", "Zlecenie zbyt duże - maksymalnie " + Fixed.qty(maxSingleBuyQty(coin)) + " szt. jednorazowo.");
        }
        double priceCents = micro / (double) Fixed.MICRO_PER_CENT;
        double cost = priceCents * q * Math.expm1(x);
        long costL = (long) Math.ceil(cost - 1e-9);
        if (costL < 1) costL = 1;
        if (costL > Fixed.MAX_MONEY) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        long newMicro = Math.max(minMicro(), Math.round(micro * Math.exp(x)));
        long avg = Fixed.mulDiv(costL, Fixed.QTY_SCALE, qty, RoundingMode.HALF_UP);
        return Result.ok(new Calc(new Quote(qty, costL, 0, costL, avg, Fixed.centsOf(micro), Fixed.centsOf(newMicro)), newMicro));
    }

    private Result<Calc> calcSell(String coin, long micro, long qty) {
        double q = depthOf(coin);
        double qc = qty / (double) Fixed.QTY_SCALE;
        double x = qc / q;
        double pc = micro / (double) Fixed.MICRO_PER_CENT;
        double minC = settings.minPrice();
        double gross;
        long newMicro;
        if (pc * Math.exp(-x) >= minC) {
            gross = pc * q * -Math.expm1(-x);
            newMicro = Math.max(minMicro(), Math.round(micro * Math.exp(-x)));
        } else {
            // cena dobiła do podłogi: reszta ilości jest sprzedawana po cenie minimalnej
            double xStar = Math.log(pc / minC);
            gross = q * (pc - minC) + minC * (qc - q * xStar);
            newMicro = minMicro();
        }
        long grossL = (long) Math.floor(gross + 1e-9);
        if (grossL < 0) grossL = 0;
        if (grossL > Fixed.MAX_MONEY * 10) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        long fee = (long) Math.ceil(grossL * (double) settings.sellFeeBp() / 10_000.0 - 1e-9);
        fee = Math.max(0, Math.min(grossL, fee));
        long net = grossL - fee;
        long avg = Fixed.mulDiv(grossL, Fixed.QTY_SCALE, qty, RoundingMode.HALF_DOWN);
        return Result.ok(new Calc(new Quote(qty, grossL, fee, net, avg, Fixed.centsOf(micro), Fixed.centsOf(newMicro)), newMicro));
    }

    /** Ile gotówki zablokować pod zlecenie kupna po limicie: najgorszy koszt przy cenie = limit (rośnie z ceną). */
    private Result<Long> lockFor(String coin, long qty, long limitCents) {
        Result<Calc> c = calcBuy(coin, Fixed.microOf(limitCents), qty);
        return c.ok ? Result.ok(c.value.q().value()) : c.failed();
    }

    // ------------------------------------------------------------------ odczyty

    public Result<Quote> quoteBuy(String coin, long qty) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (qty <= 0 || qty > Fixed.MAX_QTY) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        CoinState c = store.inTx(tx -> tx.coin(id));
        Result<Calc> r = calcBuy(id, c.price(), qty);
        return r.ok ? Result.ok(r.value.q()) : r.failed();
    }

    public Result<Quote> quoteSell(String coin, long qty) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (qty <= 0 || qty > Fixed.MAX_QTY) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        CoinState c = store.inTx(tx -> tx.coin(id));
        Result<Calc> r = calcSell(id, c.price(), qty);
        return r.ok ? Result.ok(r.value.q()) : r.failed();
    }

    /** Największa ilość, którą da się kupić za podany budżet jednym zleceniem. */
    public long maxBuyQty(String coin, long budget) {
        String id = canon(coin);
        if (id == null || budget <= 0) return 0;
        CoinState c = store.inTx(tx -> tx.coin(id));
        return maxBuyQtyAt(id, c.price(), budget);
    }

    private long maxBuyQtyAt(String id, long micro, long budget) {
        if (micro <= 0 || budget <= 0) return 0;
        long hi = Math.min(maxSingleBuyQty(id), Fixed.mulDiv(budget, 100_000_000L, micro, RoundingMode.FLOOR));
        long lo = 0;
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            Result<Calc> r = calcBuy(id, micro, mid);
            if (r.ok && r.value.q().value() <= budget) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Maksymalna ilość do kupienia dla każdej kryptowaluty za podany budżet (jedno zapytanie do bazy). */
    public Map<String, Long> maxBuyAll(long budget) {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, CoinState> byId = new HashMap<>();
        for (CoinState c : store.inTx(Tx::coins)) byId.put(c.id(), c);
        for (String id : defs.keySet()) {
            CoinState c = byId.get(id);
            out.put(id, c == null ? 0L : maxBuyQtyAt(id, c.price(), budget));
        }
        return out;
    }

    public List<CoinView> coins() {
        List<CoinView> c = cachedCoins;
        if (c != null && now() - cachedAt < CACHE_MS) return c;
        List<CoinView> fresh = store.inTx(tx -> {
            long t = now();
            List<CoinView> out = new ArrayList<>();
            Map<String, CoinState> byId = new HashMap<>();
            for (CoinState s : tx.coins()) byId.put(s.id(), s);
            for (CoinDef d : defs.values()) {
                CoinState s = byId.get(d.id());
                if (s == null) continue;
                long[] st = tx.priceStats(d.id(), t - 24L * 3600 * 1000);
                long open = st == null ? s.price() : st[0];
                long hi = st == null ? s.price() : Math.max(st[1], s.price());
                long lo = st == null ? s.price() : Math.min(st[2], s.price());
                long price = Fixed.centsOf(s.price());
                long sell = Fixed.scale(price,
                        BigDecimal.ONE.subtract(BigDecimal.valueOf(settings.sellFeeBp()).divide(BP)), RoundingMode.HALF_DOWN);
                out.add(new CoinView(d.id(), d.icon(), price, sell, s.trend(), Fixed.centsOf(open), Fixed.centsOf(hi), Fixed.centsOf(lo)));
            }
            return Collections.unmodifiableList(out);
        });
        cachedCoins = fresh;
        cachedAt = now();
        return fresh;
    }

    public Optional<CoinView> coin(String name) {
        String id = canon(name);
        if (id == null) return Optional.empty();
        for (CoinView v : coins()) if (v.id().equals(id)) return Optional.of(v);
        return Optional.empty();
    }

    public long price(String coin) {
        return coin(coin).map(CoinView::price).orElse(0L);
    }

    public Account account(String owner) {
        return store.inTx(tx -> tx.account(owner));
    }

    public Account ensureAccount(String owner, String name) {
        return run((tx, post) -> ensureAccount(tx, owner, name));
    }

    private Account ensureAccount(Tx tx, String owner, String name) {
        Account a = tx.account(owner);
        if (a == null) {
            a = new Account(owner, name, false, 0, now());
            tx.saveAccount(a);
        } else if (!a.name().equals(name) && !a.bot()) {
            a = a.withName(name);
            tx.saveAccount(a);
        }
        return a;
    }

    public PortfolioView portfolio(String owner) {
        return store.inTx(tx -> {
            Account a = tx.account(owner);
            if (a == null) return new PortfolioView(owner, "", 0, List.of(), List.of(), 0);
            Map<String, CoinState> prices = new HashMap<>();
            for (CoinState s : tx.coins()) prices.put(s.id(), s);
            List<HoldingView> hv = new ArrayList<>();
            long worth = a.balance();
            for (Holding h : tx.holdings(owner)) {
                CoinState s = prices.get(h.coin());
                if (s == null || !defs.containsKey(h.coin())) continue;
                long val = Fixed.valueMicro(h.qty(), s.price(), RoundingMode.HALF_DOWN);
                hv.add(new HoldingView(h.coin(), h.qty(), h.cost(), Fixed.centsOf(s.price()), val));
                worth += val;
            }
            List<Order> orders = tx.orders(owner);
            for (Order o : orders) worth += orderWorth(o, prices.get(o.coin()));
            return new PortfolioView(owner, a.name(), a.balance(), hv, orders, worth);
        });
    }

    private static long orderWorth(Order o, CoinState s) {
        if (o.side() == Side.BUY) return o.locked();
        return s == null ? 0 : Fixed.valueMicro(o.qty(), s.price(), RoundingMode.HALF_DOWN);
    }

    public long holdingQty(String owner, String coin) {
        Holding h = store.inTx(tx -> tx.holding(owner, coin));
        return h == null ? 0 : h.qty();
    }

    public List<Candle> candles(String coin, int tfSeconds, int limit) {
        String id = canon(coin);
        if (id == null || tfSeconds < 1) return List.of();
        limit = Math.max(1, Math.min(limit, 1000));
        long bucketMs = tfSeconds * 1000L;
        long since = now() - bucketMs * limit;
        List<PricePoint> pts = store.inTx(tx -> tx.prices(id, since));
        List<Candle> out = new ArrayList<>();
        long curBucket = Long.MIN_VALUE, o = 0, h = 0, l = 0, c = 0;
        boolean have = false;
        for (PricePoint p : pts) {
            long b = Math.floorDiv(p.ts(), bucketMs) * bucketMs;
            if (!have || b != curBucket) {
                if (have) out.add(candle(curBucket, o, h, l, c));
                long open = have ? c : p.price();
                curBucket = b; o = open; h = Math.max(open, p.price()); l = Math.min(open, p.price()); c = p.price();
                have = true;
            } else {
                h = Math.max(h, p.price()); l = Math.min(l, p.price()); c = p.price();
            }
        }
        if (have) out.add(candle(curBucket, o, h, l, c));
        return out;
    }

    private static Candle candle(long bucketMs, long o, long h, long l, long c) {
        return new Candle(bucketMs / 1000, Fixed.centsOf(o), Fixed.centsOf(h), Fixed.centsOf(l), Fixed.centsOf(c));
    }

    public List<RankEntry> ranking(int limit, boolean includeBots) {
        return store.inTx(tx -> {
            Map<String, Long> prices = new HashMap<>();
            for (CoinState s : tx.coins()) prices.put(s.id(), s.price());
            Map<String, Long> worth = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            for (Account a : tx.accounts()) {
                if (a.bot() && !includeBots) continue;
                worth.put(a.owner(), a.balance());
                names.put(a.owner(), a.name());
            }
            for (Holding h : tx.allHoldings()) {
                if (!worth.containsKey(h.owner()) || !defs.containsKey(h.coin())) continue;
                Long p = prices.get(h.coin());
                if (p != null) worth.merge(h.owner(), Fixed.valueMicro(h.qty(), p, RoundingMode.HALF_DOWN), Long::sum);
            }
            for (Order o : tx.allOrders()) {
                if (!worth.containsKey(o.owner())) continue;
                long add = o.side() == Side.BUY ? o.locked()
                        : (prices.containsKey(o.coin()) ? Fixed.valueMicro(o.qty(), prices.get(o.coin()), RoundingMode.HALF_DOWN) : 0);
                worth.merge(o.owner(), add, Long::sum);
            }
            List<RankEntry> out = new ArrayList<>();
            for (Map.Entry<String, Long> e : worth.entrySet()) out.add(new RankEntry(names.get(e.getKey()), e.getValue()));
            out.sort((a, b) -> Long.compare(b.worth(), a.worth()));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        });
    }

    public List<Trade> recentTrades(int limit) { return store.inTx(tx -> tx.recentTrades(limit)); }

    public List<Trade> tradesOf(String owner, String coin, int limit) {
        String id = coin == null ? null : canon(coin);
        return store.inTx(tx -> tx.tradesOf(owner, id, limit));
    }

    public List<NewsItem> news(int limit) { return store.inTx(tx -> tx.news(limit)); }

    public List<Order> orders(String owner) { return store.inTx(tx -> tx.orders(owner)); }

    // ------------------------------------------------------------------ wspólne wykonanie kupna / sprzedaży

    private void movePrice(Tx tx, List<Runnable> post, CoinState c, long newPrice, String forcedTrend) {
        if (newPrice == c.price() && forcedTrend == null) return;
        String trend = forcedTrend != null ? forcedTrend
                : (newPrice > c.price() ? UP : (newPrice < c.price() ? DOWN : FLAT));
        long t = now();
        tx.saveCoin(c.with(newPrice, trend, t));
        tx.insertPrice(c.id(), t, newPrice);
        long old = Fixed.centsOf(c.price());
        long neu = Fixed.centsOf(newPrice);
        if (old != neu) post.add(() -> listeners.forEach(l -> l.onPriceChange(c.id(), old, neu)));
    }

    private Result<Fill> execBuy(Tx tx, List<Runnable> post, Account acct, String coinId, long qty, String source,
                                 Funding funding, long cashLimit, Order fromOrder) {
        CoinState c = tx.coin(coinId);
        Result<Calc> cr = calcBuy(coinId, c.price(), qty);
        if (!cr.ok) return cr.failed();
        Quote q = cr.value.q();
        if (q.value() < settings.minTradeValue()) return fail("TOO_SMALL", "Transakcja jest zbyt mała.");

        switch (funding) {
            case BALANCE -> {
                if (acct.balance() < q.value())
                    return fail("INSUFFICIENT_FUNDS", "Brak środków! Potrzebujesz " + Fixed.moneyPretty(q.value()) + " " + settings.currency() + ".");
                acct = acct.withBalance(acct.balance() - q.value());
                tx.saveAccount(acct);
            }
            case ESCROW -> {
                if (q.value() > cashLimit) return fail("PRICE_MOVED", "Koszt przekracza zablokowane środki.");
                long refund = cashLimit - q.value();
                if (refund > 0) {
                    acct = acct.withBalance(acct.balance() + refund);
                    tx.saveAccount(acct);
                }
            }
            case EXTERNAL -> {
                if (q.value() > cashLimit)
                    return fail("PRICE_MOVED", "Cena zmieniła się w międzyczasie. Spróbuj ponownie.");
            }
        }

        Holding h = tx.holding(acct.owner(), coinId);
        long hq = h == null ? 0 : h.qty();
        long hc = h == null ? 0 : h.cost();
        tx.saveHolding(new Holding(acct.owner(), coinId, hq + qty, hc + q.value()));

        long tradeId = tx.nextId("trade");
        tx.insertTrade(new Trade(tradeId, now(), acct.owner(), acct.name(), Side.BUY, coinId, qty, q.value(), source));
        movePrice(tx, post, c, cr.value.newMicro(), null);

        Fill fill = new Fill(tradeId, acct.owner(), acct.name(), Side.BUY, coinId, qty, q.value(), 0, q.avgPrice(), q.priceBefore(), q.priceAfter());
        post.add(() -> listeners.forEach(l -> l.onTrade(fill, source)));
        if (fromOrder != null) post.add(() -> listeners.forEach(l -> l.onOrderFilled(fromOrder, fill)));
        return Result.ok(fill);
    }

    /**
     * @param escrowCost jeśli >= 0, sprzedaż pochodzi ze zlecenia (aktywa już zdjęte z portfela, wartość kupna = escrowCost)
     * @param credit     czy zaksięgować przychód na koncie giełdowym (false = wypłaci go wywołujący, np. przez Vault)
     */
    private Result<Fill> execSell(Tx tx, List<Runnable> post, Account acct, String coinId, long qty, String source,
                                  long escrowCost, boolean credit, Order fromOrder) {
        CoinState c = tx.coin(coinId);
        Result<Calc> cr = calcSell(coinId, c.price(), qty);
        if (!cr.ok) return cr.failed();
        Quote q = cr.value.q();
        if (q.net() < settings.minTradeValue()) return fail("TOO_SMALL", "Transakcja jest zbyt mała.");

        if (escrowCost < 0) {
            Holding h = tx.holding(acct.owner(), coinId);
            if (h == null || h.qty() < qty)
                return fail("INSUFFICIENT_HOLDINGS", "Nie masz tyle " + coinId + " w portfelu!");
            long removedCost = (h.qty() == qty) ? h.cost() : Fixed.mulDiv(h.cost(), qty, h.qty(), RoundingMode.HALF_UP);
            tx.saveHolding(new Holding(acct.owner(), coinId, h.qty() - qty, Math.max(0, h.cost() - removedCost)));
        }
        if (credit) {
            acct = acct.withBalance(acct.balance() + q.net());
            tx.saveAccount(acct);
        }

        long tradeId = tx.nextId("trade");
        tx.insertTrade(new Trade(tradeId, now(), acct.owner(), acct.name(), Side.SELL, coinId, qty, q.net(), source));
        movePrice(tx, post, c, cr.value.newMicro(), null);

        Fill fill = new Fill(tradeId, acct.owner(), acct.name(), Side.SELL, coinId, qty, q.net(), q.fee(), q.avgPrice(), q.priceBefore(), q.priceAfter());
        post.add(() -> listeners.forEach(l -> l.onTrade(fill, source)));
        if (fromOrder != null) post.add(() -> listeners.forEach(l -> l.onOrderFilled(fromOrder, fill)));
        return Result.ok(fill);
    }

    private Result<Fill> checkTradeInput(String coin, long qty) {
        if (canon(coin) == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (qty <= 0 || qty > Fixed.MAX_QTY) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        return null;
    }

    // ------------------------------------------------------------------ transakcje graczy

    /** Kupno za środki z konta giełdowego (WWW). */
    public Result<Fill> buyWithBalance(String owner, String name, String coin, long qty, String source) {
        Result<Fill> bad = checkTradeInput(coin, qty);
        if (bad != null) return bad;
        String id = canon(coin);
        return run((tx, post) -> execBuy(tx, post, ensureAccount(tx, owner, name), id, qty, source, Funding.BALANCE, 0, null));
    }

    /** Sprzedaż; przychód trafia na konto giełdowe. */
    public Result<Fill> sellToBalance(String owner, String name, String coin, long qty, String source) {
        Result<Fill> bad = checkTradeInput(coin, qty);
        if (bad != null) return bad;
        String id = canon(coin);
        return run((tx, post) -> execSell(tx, post, ensureAccount(tx, owner, name), id, qty, source, -1, true, null));
    }

    /**
     * Kupno opłacone z zewnątrz (np. z salda Vault w grze). Wywołujący pobrał już {@code paid};
     * jeśli wykonanie kosztuje mniej, różnicę ({@code paid - fill.value}) musi zwrócić.
     * Jeśli cena w międzyczasie wzrosła ponad {@code paid}, transakcja jest odrzucana (kod PRICE_MOVED)
     * i wywołujący zwraca całość.
     */
    public Result<Fill> buyPaidExternally(String owner, String name, String coin, long qty, long paid, String source) {
        Result<Fill> bad = checkTradeInput(coin, qty);
        if (bad != null) return bad;
        String id = canon(coin);
        return run((tx, post) -> execBuy(tx, post, ensureAccount(tx, owner, name), id, qty, source, Funding.EXTERNAL, paid, null));
    }

    /** Sprzedaż, której przychód wypłaca wywołujący (np. do salda Vault); {@code fill.value} to kwota do wypłaty. */
    public Result<Fill> sellForPayout(String owner, String name, String coin, long qty, String source) {
        Result<Fill> bad = checkTradeInput(coin, qty);
        if (bad != null) return bad;
        String id = canon(coin);
        return run((tx, post) -> execSell(tx, post, ensureAccount(tx, owner, name), id, qty, source, -1, false, null));
    }

    // ------------------------------------------------------------------ konto giełdowe: wpłata / wypłata

    public Result<Long> deposit(String owner, String name, long cents) {
        if (cents <= 0 || cents > Fixed.MAX_MONEY) return fail("INVALID_AMOUNT", "Niepoprawna kwota.");
        return run((tx, post) -> {
            Account a = ensureAccount(tx, owner, name);
            a = a.withBalance(a.balance() + cents);
            tx.saveAccount(a);
            return Result.ok(a.balance());
        });
    }

    /** Odejmuje środki z konta i zapisuje wypłatę w kolejce (do wykonania przez Vault). */
    public Result<Payout> requestWithdraw(String owner, long cents) {
        if (cents < Fixed.MONEY_SCALE || cents > Fixed.MAX_MONEY) return fail("INVALID_AMOUNT", "Minimalna wypłata to 1.00 " + settings.currency() + ".");
        return run((tx, post) -> {
            Account a = tx.account(owner);
            if (a == null || a.balance() < cents) return fail("INSUFFICIENT_FUNDS", "Brak środków na koncie giełdowym.");
            tx.saveAccount(a.withBalance(a.balance() - cents));
            Payout p = new Payout(tx.nextId("payout"), owner, a.name(), cents, PAYOUT_PENDING, now());
            tx.insertPayout(p);
            return Result.ok(p);
        });
    }

    public List<Payout> payouts(String status) { return store.inTx(tx -> tx.payouts(status)); }

    /** Oznacza wypłatę jako "w trakcie" PRZED wywołaniem Vault (chroni przed podwójną wypłatą po awarii). */
    public boolean markPayoutSending(long id) {
        return run((tx, post) -> {
            for (Payout p : tx.payouts(PAYOUT_PENDING)) {
                if (p.id() == id) { tx.setPayoutStatus(id, PAYOUT_SENDING); return true; }
            }
            return false;
        });
    }

    public void completePayout(long id) {
        run((tx, post) -> { tx.deletePayout(id); return null; });
    }

    /** Nieudana wypłata: zwraca środki na konto giełdowe i usuwa wpis. */
    public void failPayout(long id) {
        run((tx, post) -> {
            for (String st : new String[]{PAYOUT_SENDING, PAYOUT_PENDING}) {
                for (Payout p : tx.payouts(st)) {
                    if (p.id() != id) continue;
                    Account a = tx.account(p.owner());
                    if (a != null) tx.saveAccount(a.withBalance(a.balance() + p.amount()));
                    tx.deletePayout(id);
                    return null;
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ zlecenia limit

    public Result<Order> placeLimit(String owner, String name, Side side, String coin, long qty, long limit) {
        if (canon(coin) == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (qty <= 0 || qty > Fixed.MAX_QTY) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        if (limit < settings.minPrice() || limit > Fixed.MAX_MONEY) return fail("INVALID_PRICE", "Niepoprawna cena limit.");
        String id = canon(coin);
        return run((tx, post) -> {
            Account a = ensureAccount(tx, owner, name);
            if (tx.orders(owner).size() >= settings.maxOrdersPerPlayer())
                return fail("TOO_MANY_ORDERS", "Masz za dużo aktywnych zleceń (limit " + settings.maxOrdersPerPlayer() + ").");
            long locked;
            if (side == Side.BUY) {
                Result<Long> lr = lockFor(id, qty, limit);
                if (!lr.ok) return lr.failed();
                locked = lr.value;
                if (locked < settings.minTradeValue()) return fail("TOO_SMALL", "Zlecenie jest zbyt małe.");
                if (a.balance() < locked)
                    return fail("INSUFFICIENT_FUNDS", "Brak środków! Do zablokowania: " + Fixed.moneyPretty(locked) + " " + settings.currency() + ".");
                tx.saveAccount(a.withBalance(a.balance() - locked));
            } else {
                Holding h = tx.holding(owner, id);
                if (h == null || h.qty() < qty) return fail("INSUFFICIENT_HOLDINGS", "Nie masz tyle " + id + ", by wystawić zlecenie.");
                locked = (h.qty() == qty) ? h.cost() : Fixed.mulDiv(h.cost(), qty, h.qty(), RoundingMode.HALF_UP);
                tx.saveHolding(new Holding(owner, id, h.qty() - qty, Math.max(0, h.cost() - locked)));
            }
            Order o = new Order(tx.nextId("order"), owner, a.name(), side, id, qty, limit, locked, now());
            tx.insertOrder(o);
            return Result.ok(o);
        });
    }

    public Result<Order> cancelOrder(String owner, long orderId) {
        return run((tx, post) -> {
            Order o = tx.order(orderId);
            if (o == null || !o.owner().equals(owner)) return fail("NO_ORDER", "Nie ma takiego zlecenia.");
            refundOrder(tx, o);
            tx.deleteOrder(orderId);
            return Result.ok(o);
        });
    }

    private void refundOrder(Tx tx, Order o) {
        if (o.side() == Side.BUY) {
            Account a = tx.account(o.owner());
            if (a != null) tx.saveAccount(a.withBalance(a.balance() + o.locked()));
        } else {
            Holding h = tx.holding(o.owner(), o.coin());
            long q = h == null ? 0 : h.qty();
            long c = h == null ? 0 : h.cost();
            tx.saveHolding(new Holding(o.owner(), o.coin(), q + o.qty(), c + o.locked()));
        }
    }

    /** Realizuje zlecenia limit, których warunek cenowy jest spełniony. Zwraca liczbę zrealizowanych. */
    public int processOrders() {
        return run((tx, post) -> {
            int done = 0;
            for (Order o : tx.allOrders()) {
                if (!defs.containsKey(o.coin())) continue;
                CoinState c = tx.coin(o.coin());
                if (c == null) continue;
                long limitMicro = Fixed.microOf(o.limit());
                boolean hit = (o.side() == Side.BUY && c.price() <= limitMicro)
                        || (o.side() == Side.SELL && c.price() >= limitMicro);
                if (!hit) continue;
                Account a = tx.account(o.owner());
                if (a == null) continue;
                Result<Fill> r = (o.side() == Side.BUY)
                        ? execBuy(tx, post, a, o.coin(), o.qty(), SRC_LIMIT, Funding.ESCROW, o.locked(), o)
                        : execSell(tx, post, a, o.coin(), o.qty(), SRC_LIMIT, o.locked(), true, o);
                if (r.ok) {
                    tx.deleteOrder(o.id());
                    done++;
                } else if ("TOO_SMALL".equals(r.code) || "TOO_LARGE".equals(r.code) || "INVALID_AMOUNT".equals(r.code)) {
                    refundOrder(tx, o);
                    tx.deleteOrder(o.id());
                }
                // PRICE_MOVED (koszt > blokada): zlecenie zostaje i czeka na lepszą cenę
            }
            return done;
        });
    }

    // ------------------------------------------------------------------ ruchy cen (symulacja, eventy, admin)

    /**
     * Mnoży cenę przez {@code mult}. Jeśli {@code consumeSilent}, dolicza i zeruje "cichy trend" admina.
     */
    public Result<PriceChange> applyMultiplier(String coin, double mult, boolean consumeSilent) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        return run((tx, post) -> {
            CoinState c = tx.coin(id);
            double m = mult;
            if (consumeSilent && c.silentBp() != 0) {
                m += c.silentBp() / 10_000.0;
                c = c.withSilent(0);
            }
            long np = Math.max(minMicro(), Math.round(c.price() * m));
            String trend = np > c.price() ? UP : (np < c.price() ? DOWN : FLAT);
            long old = Fixed.centsOf(c.price());
            long neu = Fixed.centsOf(np);
            long t = now();
            tx.saveCoin(c.with(np, trend, t));
            tx.insertPrice(id, t, np);
            if (old != neu) post.add(() -> listeners.forEach(l -> l.onPriceChange(id, old, neu)));
            return Result.ok(new PriceChange(id, old, neu));
        });
    }

    /** Ustawia sztywną cenę (admin). */
    public Result<PriceChange> setPrice(String coin, long cents) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (cents < settings.minPrice() || cents > Fixed.MAX_MONEY) return fail("INVALID_PRICE", "Niepoprawna cena.");
        return run((tx, post) -> {
            CoinState c = tx.coin(id);
            long old = Fixed.centsOf(c.price());
            movePrice(tx, post, c, Fixed.microOf(cents), null);
            return Result.ok(new PriceChange(id, old, cents));
        });
    }

    /** Ustawia "cichy trend" w procentach (np. 40 = +40% przy najbliższej aktualizacji, -30 = -30%). */
    public Result<Long> setSilentTrend(String coin, double percent) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (Double.isNaN(percent) || Double.isInfinite(percent) || percent < -95 || percent > 1000)
            return fail("INVALID_AMOUNT", "Niepoprawny procent (dozwolone od -95 do 1000).");
        long bp = Math.round(percent * 100);
        return run((tx, post) -> {
            CoinState c = tx.coin(id);
            tx.saveCoin(c.withSilent(bp));
            return Result.ok(bp);
        });
    }

    public void addNews(String type, String text) {
        run((tx, post) -> {
            tx.insertNews(new NewsItem(tx.nextId("news"), now(), type, text));
            return null;
        });
    }

    // ------------------------------------------------------------------ boty

    public static String botOwner(String botName) { return "bot:" + botName; }

    /**
     * Transakcja bota: nie dotyka żadnych prawdziwych sald, tylko portfela bota, historii i ceny.
     * @param budget   (BUY) ile gotówki bot "wydaje"
     * @param fraction (SELL) jaką część posiadanej ilości bot sprzedaje (0-1)
     * @param mult     mnożnik ceny po transakcji
     */
    public Result<Fill> botTrade(String botName, String coin, Side side, long budget, double fraction, double mult) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        String owner = botOwner(botName);
        return run((tx, post) -> {
            Account a = tx.account(owner);
            if (a == null) {
                a = new Account(owner, botName, true, settings.botStartBalance(), now());
                tx.saveAccount(a);
            }
            CoinState c = tx.coin(id);
            Holding h = tx.holding(owner, id);
            long hq = h == null ? 0 : h.qty();
            long qty;
            if (side == Side.BUY) {
                qty = Fixed.mulDiv(budget, 100_000_000L, c.price(), RoundingMode.HALF_DOWN);
            } else {
                qty = Math.min(hq, (long) Math.floor(hq * fraction));
            }
            if (qty <= 0) return fail("TOO_SMALL", "Bot nie ma czym handlować.");
            long value = Fixed.valueMicro(qty, c.price(), RoundingMode.HALF_DOWN);
            if (side == Side.BUY) {
                tx.saveHolding(new Holding(owner, id, hq + qty, (h == null ? 0 : h.cost()) + value));
            } else {
                long removed = (hq == qty) ? h.cost() : Fixed.mulDiv(h.cost(), qty, hq, RoundingMode.HALF_UP);
                tx.saveHolding(new Holding(owner, id, hq - qty, Math.max(0, h.cost() - removed)));
            }
            long tradeId = tx.nextId("trade");
            tx.insertTrade(new Trade(tradeId, now(), owner, botName, side, id, qty, value, SRC_BOT));
            long np = Math.max(minMicro(), Math.round(c.price() * mult));
            movePrice(tx, post, c, np, null);
            Fill fill = new Fill(tradeId, owner, botName, side, id, qty, value, 0,
                    Fixed.centsOf(c.price()), Fixed.centsOf(c.price()), Fixed.centsOf(np));
            post.add(() -> listeners.forEach(l -> l.onTrade(fill, SRC_BOT)));
            return Result.ok(fill);
        });
    }

    // ------------------------------------------------------------------ administracja

    public enum Op { SET, ADD, SUB;
        public static Op parse(String s) {
            if (s == null) return null;
            switch (s.trim().toLowerCase()) {
                case "set": case "ustaw": return SET;
                case "add": case "dodaj": return ADD;
                case "sub": case "odejmij": return SUB;
                default: return null;
            }
        }
    }

    /** Zmiana salda gracza (grosze). Zwraca nowe saldo. */
    public Result<Long> adminBalance(String playerName, Op op, long cents) {
        if (op == null) return fail("INVALID_OP", "Niepoprawna operacja.");
        if (cents < 0 || cents > Fixed.MAX_MONEY) return fail("INVALID_AMOUNT", "Niepoprawna kwota.");
        return run((tx, post) -> {
            Account a = tx.accountByName(playerName);
            if (a == null) return fail("NO_ACCOUNT", "Nie ma takiego gracza w giełdzie (musi raz wejść na giełdę).");
            long nb = switch (op) {
                case SET -> cents;
                case ADD -> a.balance() + cents;
                case SUB -> Math.max(0, a.balance() - cents);
            };
            tx.saveAccount(a.withBalance(nb));
            return Result.ok(nb);
        });
    }

    /** Zmiana ilości kryptowaluty u gracza (1/10000 szt.). Zwraca nową ilość. */
    public Result<Long> adminHolding(String playerName, String coin, Op op, long qty) {
        String id = canon(coin);
        if (id == null) return fail("NO_COIN", "Nie ma takiej kryptowaluty.");
        if (op == null) return fail("INVALID_OP", "Niepoprawna operacja.");
        if (qty < 0 || qty > Fixed.MAX_QTY) return fail("INVALID_AMOUNT", "Niepoprawna ilość.");
        return run((tx, post) -> {
            Account a = tx.accountByName(playerName);
            if (a == null) return fail("NO_ACCOUNT", "Nie ma takiego gracza w giełdzie (musi raz wejść na giełdę).");
            CoinState c = tx.coin(id);
            Holding h = tx.holding(a.owner(), id);
            long hq = h == null ? 0 : h.qty();
            long hc = h == null ? 0 : h.cost();
            long nq = switch (op) {
                case SET -> qty;
                case ADD -> hq + qty;
                case SUB -> Math.max(0, hq - qty);
            };
            long nc;
            if (nq <= hq && hq > 0) nc = Fixed.mulDiv(hc, nq, hq, RoundingMode.HALF_UP);
            else nc = hc + Fixed.valueMicro(nq - hq, c.price(), RoundingMode.HALF_UP);
            tx.saveHolding(new Holding(a.owner(), id, nq, nc));
            return Result.ok(nq);
        });
    }

    /**
     * Reset rynku: ceny wracają do startowych, historia i newsy są czyszczone.
     * Z {@code wipePlayers} dodatkowo kasowane są portfele i zlecenia, a salda graczy zerowane.
     */
    public void resetMarket(boolean wipePlayers) {
        run((tx, post) -> {
            if (wipePlayers) tx.wipePlayers();
            else for (Order o : tx.allOrders()) { refundOrder(tx, o); tx.deleteOrder(o.id()); }
            tx.wipeHistory();
            long t = now();
            for (CoinDef d : defs.values()) {
                long p = Fixed.microOf(Math.max(settings.minPrice(), d.startPrice()));
                tx.saveCoin(new CoinState(d.id(), p, FLAT, 0, t));
                tx.insertPrice(d.id(), t, p);
            }
            return null;
        });
    }

    /** Usuwa starą historię cen (zgodnie z retencją z configu). */
    public void prune() {
        run((tx, post) -> {
            long t = now();
            tx.pruneOlderThan(t - settings.historyRetentionMs());
            tx.thinPrices(t - THIN_AFTER_MS, THIN_BUCKET_MS);
            return null;
        });
    }

    /** Punkty cen starsze niż 48 h są przerzedzane do kilku na 5 minut (oszczędność miejsca przy mikro-ruchach). */
    private static final long THIN_AFTER_MS = 48L * 3600 * 1000;
    private static final long THIN_BUCKET_MS = 5L * 60 * 1000;
}
