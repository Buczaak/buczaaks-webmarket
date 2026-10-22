package pl.kryptogielda.web;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pl.kryptogielda.core.*;
import pl.kryptogielda.core.Model.*;
import pl.kryptogielda.sim.SimConfig.EventDef;
import pl.kryptogielda.web.Sessions.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Wbudowany serwer WWW: statyczna strona + API JSON. Działa na JDK (bez dodatkowych bibliotek).
 *
 * <p>Zabezpieczenia: logowanie jednorazowym kodem z gry, ciasteczko HttpOnly + SameSite=Strict,
 * wymagany Content-Type: application/json dla zapytań zmieniających stan + kontrola nagłówka Origin,
 * limity zapytań (IP i sesja), limit wielkości ciała zapytania, nagłówki CSP. Ceny liczy wyłącznie serwer.
 */
public final class WebServer {

    public record Config(boolean enabled, String bind, int port, String publicUrl, boolean secureCookie,
                         boolean trustForwarded, int sessionHours, int linkCodeMinutes,
                         boolean rankingBots, int rankingSize, Path overrideDir, List<String> allowedOrigins,
                         String title, boolean labelBots, int rateScale) {
        public static Config defaults() {
            return new Config(true, "0.0.0.0", 8123, "", false, false, 12, 2, false, 50, null, List.of(), "Giełda Krypto", true, 1);
        }
    }

    /** Połączenie ze światem gry (implementowane przez plugin). */
    public interface GameBridge {
        /** Przetwórz oczekujące wypłaty do gry (Vault). */
        void kickPayouts();
        void forceMarketUpdate();
        List<EventDef> events();
        void runEvent(int index);
        void announceReset();
        /** Wywoływane po akcji admina - do logu. */
        void auditLog(String adminName, String action);
    }

    private static final class ApiError extends RuntimeException {
        final int status;
        final String code;
        ApiError(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern STATIC_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_BODY = 4096;
    private static final Set<Integer> TIMEFRAMES = Set.of(60, 300, 900, 3600, 14400);

    private final Config cfg;
    private final MarketService market;
    private final GameBridge bridge;
    private final Logger log;
    private final LongSupplier clock;
    private final Sessions sessions;
    private final LinkCodes linkCodes;
    private final RateLimiter limiter = new RateLimiter();
    private HttpServer server;
    private ScheduledExecutorService janitor;
    private ThreadPoolExecutor pool;

    public WebServer(Config cfg, MarketService market, GameBridge bridge, Logger log, LongSupplier clock) {
        this.cfg = cfg;
        this.market = market;
        this.bridge = bridge;
        this.log = log;
        this.clock = clock;
        this.sessions = new Sessions(cfg.sessionHours() * 3600_000L);
        this.linkCodes = new LinkCodes(cfg.linkCodeMinutes() * 60_000L);
    }

    public LinkCodes linkCodes() { return linkCodes; }

    public Sessions sessions() { return sessions; }

    public int port() { return server == null ? -1 : server.getAddress().getPort(); }

    // ------------------------------------------------------------------ cykl życia

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(cfg.bind(), cfg.port()), 64);
        pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "KryptoGielda-Web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KryptoGielda-Web-Janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(() -> sessions.purge(clock.getAsLong()), 1, 1, TimeUnit.MINUTES);
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
        if (janitor != null) janitor.shutdownNow();
        server = null;
    }

    // ------------------------------------------------------------------ obsługa żądań

    private void handle(HttpExchange ex) throws IOException {
        try {
            addSecurityHeaders(ex);
            String path = ex.getRequestURI().getPath();
            if (path == null) path = "/";
            if (path.startsWith("/api/")) {
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                api(ex, path.substring(5));
            } else {
                staticFile(ex, path);
            }
        } catch (ApiError e) {
            sendJson(ex, e.status, errorBody(e.code, e.getMessage()));
        } catch (Throwable t) {
            log.log(Level.WARNING, "Błąd obsługi żądania WWW " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), t);
            try {
                sendJson(ex, 500, errorBody("INTERNAL", "Wewnętrzny błąd serwera."));
            } catch (Throwable ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private void addSecurityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                + "connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    }

    // ------------------------------------------------------------------ pliki statyczne

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "txt", "text/plain; charset=utf-8");

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String m = ex.getRequestMethod();
        if (!m.equals("GET") && !m.equals("HEAD")) throw new ApiError(405, "METHOD", "Metoda niedozwolona.");
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!STATIC_NAME.matcher(name).matches()) throw new ApiError(404, "NOT_FOUND", "Nie znaleziono.");
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase());
        if (type == null) throw new ApiError(404, "NOT_FOUND", "Nie znaleziono.");

        byte[] data = null;
        if (cfg.overrideDir() != null) {
            Path f = cfg.overrideDir().resolve(name).normalize();
            if (f.startsWith(cfg.overrideDir().normalize()) && Files.isRegularFile(f)) data = Files.readAllBytes(f);
        }
        if (data == null) {
            try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
                if (in == null) throw new ApiError(404, "NOT_FOUND", "Nie znaleziono.");
                data = in.readAllBytes();
            }
        }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", name.equals("index.html") ? "no-cache" : "public, max-age=300");
        if (m.equals("HEAD")) {
            h.set("Content-Length", String.valueOf(data.length));
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    // ------------------------------------------------------------------ pomocnicze HTTP

    private String clientIp(HttpExchange ex) {
        if (cfg.trustForwarded()) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty() && first.length() < 64) return first;
            }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private String cookie(HttpExchange ex, String name) {
        List<String> hs = ex.getRequestHeaders().get("Cookie");
        if (hs == null) return null;
        for (String h : hs) {
            for (String part : h.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
            }
        }
        return null;
    }

    private Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.length() > 512) return out;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json"))
            throw new ApiError(415, "CONTENT_TYPE", "Wymagany Content-Type: application/json.");
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            try {
                if (Long.parseLong(len.trim()) > MAX_BODY) throw new ApiError(413, "TOO_BIG", "Zbyt duże żądanie.");
            } catch (NumberFormatException e) {
                throw new ApiError(400, "BAD_REQUEST", "Niepoprawny nagłówek.");
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BODY) throw new ApiError(413, "TOO_BIG", "Zbyt duże żądanie.");
            }
        }
        try {
            JsonElement e = JsonParser.parseString(bos.toString(StandardCharsets.UTF_8));
            if (!e.isJsonObject()) throw new ApiError(400, "BAD_REQUEST", "Oczekiwano obiektu JSON.");
            return e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ApiError(400, "BAD_REQUEST", "Niepoprawny JSON.");
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        if (cfg.allowedOrigins().contains(origin)) return;
        String host = ex.getRequestHeaders().getFirst("Host");
        String authority = origin.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        if (host == null || !authority.equalsIgnoreCase(host)) {
            throw new ApiError(403, "ORIGIN", "Niedozwolone źródło żądania.");
        }
    }

    private void sendJson(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] data = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
    }

    private static JsonObject errorBody(String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        o.addProperty("message", message);
        return o;
    }

    private Session session(HttpExchange ex) {
        return sessions.get(cookie(ex, "kg_session"), clock.getAsLong());
    }

    private Session requireSession(HttpExchange ex) {
        Session s = session(ex);
        if (s == null) throw new ApiError(401, "UNAUTHORIZED", "Zaloguj się kodem z gry (komenda /gielda link).");
        return s;
    }

    private Session requireAdmin(HttpExchange ex) {
        Session s = requireSession(ex);
        if (!s.admin) throw new ApiError(403, "FORBIDDEN", "Brak uprawnień administratora.");
        return s;
    }

    /**
     * Limit zapytań dla adresu IP. Za tunelem (frp/proxy bez przekazywania adresu) wszystkie zapytania wyglądają
     * jak z 127.0.0.1, więc dla adresu pętli zwrotnej limity są mnożone (min. x20), żeby nie blokować wszystkich graczy naraz.
     */
    private void ipLimit(String kind, String ip, int max, long windowMs, String what) {
        int scale = Math.max(1, cfg.rateScale());
        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1")) scale = Math.max(scale, 20);
        limit(kind + ":" + ip, (int) Math.min(Integer.MAX_VALUE, (long) max * scale), windowMs, what);
    }

    private void limit(String key, int max, long windowMs, String what) {
        if (!limiter.allow(key, max, windowMs, clock.getAsLong()))
            throw new ApiError(429, "RATE_LIMIT", "Zbyt wiele zapytań (" + what + "). Zwolnij na chwilę.");
    }

    // ------------------------------------------------------------------ API

    private void api(HttpExchange ex, String route) throws IOException {
        String method = ex.getRequestMethod();
        String ip = clientIp(ex);
        boolean post = method.equals("POST");
        if (!post && !method.equals("GET")) throw new ApiError(405, "METHOD", "Metoda niedozwolona.");
        if (post) {
            checkOrigin(ex);
            ipLimit("post", ip, 120, 60_000, "zapis");
        } else {
            ipLimit("get", ip, 900, 60_000, "odczyt");
        }

        switch (route) {
            // --- publiczne odczyty
            case "market" -> { need(method, "GET"); sendJson(ex, 200, marketJson()); }
            case "candles" -> { need(method, "GET"); sendJson(ex, 200, candlesJson(query(ex))); }
            case "ranking" -> { need(method, "GET"); sendJson(ex, 200, rankingJson()); }
            case "quote" -> { need(method, "GET"); sendJson(ex, 200, quoteJson(query(ex))); }
            // --- sesja
            case "login" -> { need(method, "POST"); login(ex, ip); }
            case "logout" -> { need(method, "POST"); logout(ex); }
            // --- konto
            case "me" -> { need(method, "GET"); sendJson(ex, 200, meJson(requireSession(ex))); }
            case "trades" -> { need(method, "GET"); sendJson(ex, 200, tradesJson(requireSession(ex), query(ex))); }
            case "trade" -> { need(method, "POST"); trade(ex); }
            case "order" -> { need(method, "POST"); order(ex); }
            case "cancel" -> { need(method, "POST"); cancel(ex); }
            case "withdraw" -> { need(method, "POST"); withdraw(ex); }
            // --- administracja
            case "admin/info" -> { need(method, "GET"); sendJson(ex, 200, adminInfo(requireAdmin(ex))); }
            case "admin/price" -> { need(method, "POST"); adminPrice(ex); }
            case "admin/trend" -> { need(method, "POST"); adminTrend(ex); }
            case "admin/force" -> { need(method, "POST"); adminForce(ex); }
            case "admin/event" -> { need(method, "POST"); adminEvent(ex); }
            case "admin/reset" -> { need(method, "POST"); adminReset(ex); }
            case "admin/player" -> { need(method, "POST"); adminPlayer(ex); }
            default -> throw new ApiError(404, "NOT_FOUND", "Nie znaleziono.");
        }
    }

    private static void need(String actual, String expected) {
        if (!actual.equals(expected)) throw new ApiError(405, "METHOD", "Metoda niedozwolona.");
    }

    // --- JSON: widoki

    private JsonObject marketJson() {
        JsonObject o = new JsonObject();
        Settings st = market.settings();
        o.addProperty("currency", st.currency());
        o.addProperty("feeBp", st.sellFeeBp());
        o.addProperty("time", clock.getAsLong());
        o.addProperty("title", cfg.title());
        o.addProperty("labelBots", cfg.labelBots());
        JsonArray coins = new JsonArray();
        for (MarketService.CoinView c : market.coins()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id());
            j.addProperty("icon", c.icon());
            j.addProperty("price", c.price());
            j.addProperty("sellPrice", c.sellPrice());
            j.addProperty("trend", c.trend());
            j.addProperty("open24h", c.open24h());
            j.addProperty("high24h", c.high24h());
            j.addProperty("low24h", c.low24h());
            j.addProperty("maxBuyQty", market.maxSingleBuyQty(c.id()));
            coins.add(j);
        }
        o.add("coins", coins);
        JsonArray news = new JsonArray();
        for (NewsItem n : market.news(8)) {
            JsonObject j = new JsonObject();
            j.addProperty("ts", n.ts());
            j.addProperty("type", n.type());
            j.addProperty("text", n.text());
            news.add(j);
        }
        o.add("news", news);
        JsonArray feed = new JsonArray();
        for (Trade t : market.recentTrades(12)) feed.add(tradeJson(t));
        o.add("feed", feed);
        return o;
    }

    private static JsonObject tradeJson(Trade t) {
        JsonObject j = new JsonObject();
        j.addProperty("id", t.id());
        j.addProperty("ts", t.ts());
        j.addProperty("name", t.name());
        j.addProperty("side", t.side().name());
        j.addProperty("coin", t.coin());
        j.addProperty("qty", t.qty());
        j.addProperty("value", t.value());
        j.addProperty("source", t.source());
        return j;
    }

    private JsonObject candlesJson(Map<String, String> q) {
        String coin = market.canon(q.get("coin"));
        if (coin == null) throw new ApiError(400, "NO_COIN", "Nie ma takiej kryptowaluty.");
        int tf = parseInt(q.get("tf"), 300);
        if (!TIMEFRAMES.contains(tf)) throw new ApiError(400, "BAD_REQUEST", "Niepoprawny interwał.");
        int limit = Math.max(10, Math.min(parseInt(q.get("limit"), 300), 1000));
        JsonObject o = new JsonObject();
        o.addProperty("coin", coin);
        o.addProperty("tf", tf);
        JsonArray arr = new JsonArray();
        for (Candle c : market.candles(coin, tf, limit)) {
            JsonArray a = new JsonArray();
            a.add(c.t()); a.add(c.o()); a.add(c.h()); a.add(c.l()); a.add(c.c());
            arr.add(a);
        }
        o.add("candles", arr);
        return o;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private JsonObject rankingJson() {
        JsonArray rows = new JsonArray();
        for (RankEntry r : market.ranking(cfg.rankingSize(), cfg.rankingBots())) {
            JsonObject j = new JsonObject();
            j.addProperty("name", r.name());
            j.addProperty("worth", r.worth());
            rows.add(j);
        }
        JsonObject o = new JsonObject();
        o.add("rows", rows);
        return o;
    }

    private JsonObject quoteJson(Map<String, String> q) {
        String side = q.get("side");
        long qty = Fixed.parseQty(q.get("qty"));
        if (qty == Fixed.INVALID) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawna ilość.");
        Result<MarketService.Quote> r;
        if ("buy".equals(side)) r = market.quoteBuy(q.get("coin"), qty);
        else if ("sell".equals(side)) r = market.quoteSell(q.get("coin"), qty);
        else throw new ApiError(400, "BAD_REQUEST", "Niepoprawny kierunek.");
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        MarketService.Quote x = r.value;
        JsonObject o = new JsonObject();
        o.addProperty("value", x.value());
        o.addProperty("fee", x.fee());
        o.addProperty("net", x.net());
        o.addProperty("avgPrice", x.avgPrice());
        o.addProperty("priceBefore", x.priceBefore());
        o.addProperty("priceAfter", x.priceAfter());
        return o;
    }

    private JsonObject meJson(Session s) {
        MarketService.PortfolioView p = market.portfolio(s.owner);
        JsonObject o = new JsonObject();
        o.addProperty("name", s.name);
        o.addProperty("admin", s.admin);
        o.addProperty("balance", p.balance());
        o.addProperty("netWorth", p.netWorth());
        JsonArray hs = new JsonArray();
        for (MarketService.HoldingView h : p.holdings()) {
            JsonObject j = new JsonObject();
            j.addProperty("coin", h.coin());
            j.addProperty("qty", h.qty());
            j.addProperty("cost", h.cost());
            j.addProperty("price", h.price());
            j.addProperty("value", h.value());
            hs.add(j);
        }
        o.add("holdings", hs);
        JsonArray os = new JsonArray();
        for (Order ord : p.orders()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", ord.id());
            j.addProperty("side", ord.side().name());
            j.addProperty("coin", ord.coin());
            j.addProperty("qty", ord.qty());
            j.addProperty("limit", ord.limit());
            j.addProperty("locked", ord.locked());
            j.addProperty("ts", ord.created());
            os.add(j);
        }
        o.add("orders", os);
        JsonObject maxBuy = new JsonObject();
        for (Map.Entry<String, Long> e : market.maxBuyAll(p.balance()).entrySet()) maxBuy.addProperty(e.getKey(), e.getValue());
        o.add("maxBuy", maxBuy);
        return o;
    }

    private JsonObject tradesJson(Session s, Map<String, String> q) {
        String coin = q.get("coin");
        if (coin != null && !coin.isEmpty() && market.canon(coin) == null) throw new ApiError(400, "NO_COIN", "Nie ma takiej kryptowaluty.");
        int limit = Math.max(1, Math.min(parseInt(q.get("limit"), 20), 100));
        JsonArray arr = new JsonArray();
        for (Trade t : market.tradesOf(s.owner, coin == null || coin.isEmpty() ? null : coin, limit)) arr.add(tradeJson(t));
        JsonObject o = new JsonObject();
        o.add("trades", arr);
        return o;
    }

    private static JsonObject fillJson(Fill f) {
        JsonObject j = new JsonObject();
        j.addProperty("tradeId", f.tradeId());
        j.addProperty("side", f.side().name());
        j.addProperty("coin", f.coin());
        j.addProperty("qty", f.qty());
        j.addProperty("value", f.value());
        j.addProperty("fee", f.fee());
        j.addProperty("avgPrice", f.avgPrice());
        j.addProperty("priceBefore", f.priceBefore());
        j.addProperty("priceAfter", f.priceAfter());
        return j;
    }

    // --- akcje

    private void login(HttpExchange ex, String ip) throws IOException {
        ipLimit("login", ip, 8, 300_000, "logowanie");
        JsonObject body = readJson(ex);
        LinkCodes.Info info = linkCodes.consume(str(body, "code"), clock.getAsLong());
        if (info == null) throw new ApiError(401, "BAD_CODE", "Błędny lub wygasły kod. Wpisz w grze /gielda link i spróbuj ponownie.");
        market.ensureAccount(info.owner(), info.name());
        Session s = sessions.create(info.owner(), info.name(), info.admin(), clock.getAsLong());
        String cookie = "kg_session=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (sessions.ttlMs() / 1000)
                + (cfg.secureCookie() ? "; Secure" : "");
        ex.getResponseHeaders().add("Set-Cookie", cookie);
        JsonObject o = new JsonObject();
        o.addProperty("name", s.name);
        o.addProperty("admin", s.admin);
        sendJson(ex, 200, o);
    }

    private void logout(HttpExchange ex) throws IOException {
        sessions.remove(cookie(ex, "kg_session"));
        ex.getResponseHeaders().add("Set-Cookie", "kg_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                + (cfg.secureCookie() ? "; Secure" : ""));
        sendJson(ex, 200, new JsonObject());
    }

    private void trade(HttpExchange ex) throws IOException {
        Session s = requireSession(ex);
        limit("trade:" + s.owner, 30, 10_000, "handel");
        JsonObject b = readJson(ex);
        Side side = Side.parse(str(b, "side"));
        long qty = Fixed.parseQty(str(b, "qty"));
        if (side == null) throw new ApiError(400, "BAD_REQUEST", "Niepoprawny kierunek.");
        if (qty == Fixed.INVALID) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawna ilość.");
        Result<Fill> r = side == Side.BUY
                ? market.buyWithBalance(s.owner, s.name, str(b, "coin"), qty, MarketService.SRC_WEB)
                : market.sellToBalance(s.owner, s.name, str(b, "coin"), qty, MarketService.SRC_WEB);
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        sendJson(ex, 200, fillJson(r.value));
    }

    private void order(HttpExchange ex) throws IOException {
        Session s = requireSession(ex);
        limit("trade:" + s.owner, 30, 10_000, "handel");
        JsonObject b = readJson(ex);
        Side side = Side.parse(str(b, "side"));
        long qty = Fixed.parseQty(str(b, "qty"));
        long price = Fixed.parseMoney(str(b, "price"));
        if (side == null) throw new ApiError(400, "BAD_REQUEST", "Niepoprawny kierunek.");
        if (qty == Fixed.INVALID) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawna ilość.");
        if (price == Fixed.INVALID || price <= 0) throw new ApiError(400, "INVALID_PRICE", "Niepoprawna cena limit.");
        Result<Order> r = market.placeLimit(s.owner, s.name, side, str(b, "coin"), qty, price);
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        JsonObject o = new JsonObject();
        o.addProperty("id", r.value.id());
        sendJson(ex, 200, o);
    }

    private void cancel(HttpExchange ex) throws IOException {
        Session s = requireSession(ex);
        limit("trade:" + s.owner, 30, 10_000, "handel");
        JsonObject b = readJson(ex);
        long id;
        try {
            id = Long.parseLong(str(b, "id"));
        } catch (NumberFormatException | NullPointerException e) {
            throw new ApiError(400, "BAD_REQUEST", "Niepoprawny numer zlecenia.");
        }
        Result<Order> r = market.cancelOrder(s.owner, id);
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        sendJson(ex, 200, new JsonObject());
    }

    private void withdraw(HttpExchange ex) throws IOException {
        Session s = requireSession(ex);
        limit("withdraw:" + s.owner, 5, 60_000, "wypłaty");
        JsonObject b = readJson(ex);
        long cents = Fixed.parseMoney(str(b, "amount"));
        if (cents == Fixed.INVALID) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawna kwota.");
        Result<Payout> r = market.requestWithdraw(s.owner, cents);
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        bridge.kickPayouts();
        JsonObject o = new JsonObject();
        o.addProperty("queued", true);
        o.addProperty("amount", cents);
        sendJson(ex, 200, o);
    }

    // --- administracja

    private JsonObject adminInfo(Session s) {
        JsonObject o = new JsonObject();
        JsonArray evs = new JsonArray();
        List<EventDef> list = bridge.events();
        for (int i = 0; i < list.size(); i++) {
            EventDef e = list.get(i);
            JsonObject j = new JsonObject();
            j.addProperty("index", i);
            j.addProperty("type", e.type());
            j.addProperty("text", e.text());
            j.addProperty("coin", e.coin());
            j.addProperty("multiplier", e.multiplier());
            evs.add(j);
        }
        o.add("events", evs);
        return o;
    }

    private void adminPrice(HttpExchange ex) throws IOException {
        Session s = requireAdmin(ex);
        JsonObject b = readJson(ex);
        String mode = str(b, "mode");
        String coin = str(b, "coin");
        String value = str(b, "value");
        Result<MarketService.PriceChange> r;
        if ("set".equals(mode)) {
            long cents = Fixed.parseMoney(value);
            if (cents == Fixed.INVALID) throw new ApiError(400, "INVALID_PRICE", "Niepoprawna cena.");
            r = market.setPrice(coin, cents);
        } else if ("pump".equals(mode) || "dump".equals(mode)) {
            double pct = parsePercent(value);
            if (pct <= 0 || pct > 1000 || ("dump".equals(mode) && pct >= 95)) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawny procent.");
            double mult = "pump".equals(mode) ? 1 + pct / 100.0 : 1 - pct / 100.0;
            r = market.applyMultiplier(coin, mult, false);
        } else {
            throw new ApiError(400, "BAD_REQUEST", "Niepoprawny tryb.");
        }
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        bridge.auditLog(s.name, "cena " + mode + " " + coin + " " + value + " (" + Fixed.money(r.value.oldPrice()) + " -> " + Fixed.money(r.value.newPrice()) + ")");
        sendJson(ex, 200, new JsonObject());
    }

    private static double parsePercent(String s) {
        if (s == null) return Double.NaN;
        try {
            String t = s.trim().replace(',', '.');
            if (!t.matches("-?[0-9]{1,6}(\\.[0-9]{1,4})?")) return Double.NaN;
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void adminTrend(HttpExchange ex) throws IOException {
        Session s = requireAdmin(ex);
        JsonObject b = readJson(ex);
        double pct = parsePercent(str(b, "percent"));
        Result<Long> r = market.setSilentTrend(str(b, "coin"), pct);
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        bridge.auditLog(s.name, "cichy trend " + str(b, "coin") + " " + pct + "%");
        sendJson(ex, 200, new JsonObject());
    }

    private void adminForce(HttpExchange ex) throws IOException {
        Session s = requireAdmin(ex);
        readJson(ex);
        bridge.forceMarketUpdate();
        bridge.auditLog(s.name, "wymuszona aktualizacja rynku");
        sendJson(ex, 200, new JsonObject());
    }

    private void adminEvent(HttpExchange ex) throws IOException {
        Session s = requireAdmin(ex);
        JsonObject b = readJson(ex);
        int idx = parseInt(str(b, "index"), -1);
        List<EventDef> list = bridge.events();
        if (idx < 0 || idx >= list.size()) throw new ApiError(400, "BAD_REQUEST", "Niepoprawny event.");
        bridge.runEvent(idx);
        bridge.auditLog(s.name, "event #" + idx + " " + list.get(idx).type());
        sendJson(ex, 200, new JsonObject());
    }

    private void adminReset(HttpExchange ex) throws IOException {
        Session s = requireAdmin(ex);
        JsonObject b = readJson(ex);
        if (!"RESET".equals(str(b, "confirm"))) throw new ApiError(400, "CONFIRM", "Wpisz RESET, aby potwierdzić.");
        boolean wipe = "true".equals(str(b, "wipePlayers"));
        market.resetMarket(wipe);
        bridge.announceReset();
        bridge.auditLog(s.name, "reset rynku, wipePlayers=" + wipe);
        sendJson(ex, 200, new JsonObject());
    }

    private void adminPlayer(HttpExchange ex) throws IOException {
        Session s = requireAdmin(ex);
        JsonObject b = readJson(ex);
        String player = str(b, "player");
        String asset = str(b, "asset");
        MarketService.Op op = MarketService.Op.parse(str(b, "op"));
        String value = str(b, "value");
        if (player == null || player.isBlank() || asset == null) throw new ApiError(400, "BAD_REQUEST", "Brak danych.");
        Result<Long> r;
        if ("BALANCE".equals(asset)) {
            long cents = Fixed.parseMoney(value);
            if (cents == Fixed.INVALID) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawna kwota.");
            r = market.adminBalance(player.trim(), op, cents);
        } else {
            long qty = Fixed.parseQty(value);
            if (qty == Fixed.INVALID) throw new ApiError(400, "INVALID_AMOUNT", "Niepoprawna ilość.");
            r = market.adminHolding(player.trim(), asset, op, qty);
        }
        if (!r.ok) throw new ApiError(400, r.code, r.message);
        bridge.auditLog(s.name, "gracz " + player + " " + asset + " " + str(b, "op") + " " + value);
        sendJson(ex, 200, new JsonObject());
    }
}
