package pl.kryptogielda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kryptogielda.core.*;
import pl.kryptogielda.sim.SimConfig;
import pl.kryptogielda.store.JdbcStore;
import pl.kryptogielda.web.WebServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {

    @TempDir Path tmp;
    JdbcStore store;
    MarketService market;
    WebServer web;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    final AtomicInteger kicks = new AtomicInteger();
    final AtomicInteger forced = new AtomicInteger();
    final List<String> audit = new ArrayList<>();
    final List<Integer> ranEvents = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        store = new JdbcStore("jdbc:sqlite:" + tmp.resolve("w.db"), null, null);
        market = new MarketService(store, Settings.defaults(), System::currentTimeMillis, Logger.getLogger("t"));
        market.init(List.of(new CoinDef("MineCoin", 10_000, "GOLD_NUGGET", 0.96, 1.04),
                new CoinDef("CreepDoge", 1_000, "GUNPOWDER", 0.85, 1.15)));
        WebServer.GameBridge bridge = new WebServer.GameBridge() {
            public void kickPayouts() { kicks.incrementAndGet(); }
            public void forceMarketUpdate() { forced.incrementAndGet(); }
            public List<SimConfig.EventDef> events() { return SimConfig.defaults().events(); }
            public int configEventCount() { return SimConfig.defaults().events().size(); }
            public int importDefaultEvents() { return 0; }
            public String language() { return "pl"; }
            public String version() { return "1.0.2"; }
            public void runEvent(int i) { ranEvents.add(i); }
            public void announceReset() {}
            public void auditLog(String n, String a) { audit.add(n + ": " + a); }
        };
        Path web0 = tmp.resolve("override");
        Files.createDirectories(web0);
        Files.writeString(web0.resolve("index.html"), "<html>test</html>");
        WebServer.Config c = new WebServer.Config(true, "127.0.0.1", 0, "", false, false, 12, 2, false, 50, web0, List.of(), "Test", true, 1, "pl");
        web = new WebServer(c, market, bridge, Logger.getLogger("t"), System::currentTimeMillis);
        web.start();
        base = "http://127.0.0.1:" + web.port();
    }

    @AfterEach
    void tearDown() {
        web.stop();
        store.close();
    }

    // ------------------------------------------------------------------ helpers

    record Resp(int status, String body, java.net.http.HttpHeaders headers) {
        JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
        String cookie() {
            for (String c : headers.allValues("set-cookie")) if (c.startsWith("kg_session=")) return c.split(";")[0];
            return null;
        }
    }

    Resp get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) b.header("Cookie", cookie);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    Resp post(String path, String json, String cookie, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (cookie != null) b.header("Cookie", cookie);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) b.header(extraHeaders[i], extraHeaders[i + 1]);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    String login(String uuid, String name, boolean admin) throws Exception {
        String code = web.linkCodes().create(uuid, name, admin, System.currentTimeMillis());
        Resp r = post("/api/login", "{\"code\":\"" + code + "\"}", null);
        assertEquals(200, r.status, r.body);
        assertNotNull(r.cookie());
        return r.cookie();
    }

    // ------------------------------------------------------------------ publiczne

    @Test
    void marketIsPublic() throws Exception {
        Resp r = get("/api/market", null);
        assertEquals(200, r.status);
        JsonObject j = r.json();
        assertEquals("PLN", j.get("currency").getAsString());
        JsonArray coins = j.getAsJsonArray("coins");
        assertEquals(2, coins.size());
        assertEquals(10_000, coins.get(0).getAsJsonObject().get("price").getAsLong());
        assertTrue(coins.get(0).getAsJsonObject().get("maxBuyQty").getAsLong() > 0);
    }

    @Test
    void securityHeadersEverywhere() throws Exception {
        Resp r = get("/api/market", null);
        assertEquals("nosniff", r.headers.firstValue("x-content-type-options").orElse(""));
        assertEquals("DENY", r.headers.firstValue("x-frame-options").orElse(""));
        assertTrue(r.headers.firstValue("content-security-policy").orElse("").contains("default-src 'none'"));
        assertEquals("no-store", r.headers.firstValue("cache-control").orElse(""));
        Resp s = get("/", null);
        assertEquals(200, s.status);
        assertTrue(s.headers.firstValue("content-security-policy").isPresent());
    }

    @Test
    void candlesQuoteRanking() throws Exception {
        market.setPrice("MineCoin", 10_500);
        Resp c = get("/api/candles?coin=minecoin&tf=300&limit=50", null);
        assertEquals(200, c.status);
        assertTrue(c.json().getAsJsonArray("candles").size() >= 1);
        assertEquals(400, get("/api/candles?coin=Nope&tf=300", null).status);
        assertEquals(400, get("/api/candles?coin=MineCoin&tf=7", null).status);

        Resp q = get("/api/quote?side=buy&coin=MineCoin&qty=2", null);
        assertEquals(200, q.status);
        assertTrue(q.json().get("value").getAsLong() > 0);
        assertEquals(400, get("/api/quote?side=buy&coin=MineCoin&qty=abc", null).status);
        assertEquals(400, get("/api/quote?side=hold&coin=MineCoin&qty=1", null).status);

        assertEquals(200, get("/api/ranking", null).status);
    }

    @Test
    void unknownRoutesAndMethods() throws Exception {
        assertEquals(404, get("/api/nothing", null).status);
        assertEquals(405, post("/api/market", "{}", null).status);
        assertEquals(405, get("/api/login", null).status);
        HttpResponse<String> del = http.send(HttpRequest.newBuilder(URI.create(base + "/api/market"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, del.statusCode());
    }

    @Test
    void staticFilesAreSafe() throws Exception {
        assertEquals("<html>test</html>", get("/", null).body, "plik z katalogu nadpisań");
        assertEquals(404, get("/..%2f..%2fetc%2fpasswd", null).status);
        assertEquals(404, get("/%2e%2e/pom.xml", null).status);
        assertEquals(404, get("/secret.exe", null).status);
        assertEquals(404, get("/a/b.js", null).status);
        assertEquals(404, get("/index.html.bak", null).status);
    }

    // ------------------------------------------------------------------ logowanie

    @Test
    void meRequiresLogin() throws Exception {
        assertEquals(401, get("/api/me", null).status);
        assertEquals(401, get("/api/me", "kg_session=garbage").status);
        assertEquals(401, post("/api/trade", "{}", null).status);
    }

    @Test
    void loginFlowAndCookieAttributes() throws Exception {
        String code = web.linkCodes().create("u1", "Ala", false, System.currentTimeMillis());
        Resp bad = post("/api/login", "{\"code\":\"ZZZZZZ\"}", null);
        assertEquals(401, bad.status);
        Resp ok = post("/api/login", "{\"code\":\"" + code.toLowerCase() + "\"}", null);
        assertEquals(200, ok.status);
        String raw = ok.headers.allValues("set-cookie").get(0);
        assertTrue(raw.contains("HttpOnly"));
        assertTrue(raw.contains("SameSite=Strict"));
        // kod jednorazowy
        assertEquals(401, post("/api/login", "{\"code\":\"" + code + "\"}", null).status);
        Resp me = get("/api/me", ok.cookie());
        assertEquals("Ala", me.json().get("name").getAsString());
        assertFalse(me.json().get("admin").getAsBoolean());
        // wylogowanie unieważnia sesję
        assertEquals(200, post("/api/logout", "{}", ok.cookie()).status);
        assertEquals(401, get("/api/me", ok.cookie()).status);
    }

    @Test
    void loginIsRateLimited() throws Exception {
        // test łączy się z 127.0.0.1 (jak tunel frp), więc limit 8/5 min jest mnożony x20 = 160 prób
        int limited = 0;
        for (int i = 0; i < 180; i++) {
            if (post("/api/login", "{\"code\":\"AAAAAA\"}", null).status == 429) limited++;
        }
        assertTrue(limited > 0, "po wielu próbach logowanie jest blokowane");
        assertTrue(limited < 40, "limit dla adresu lokalnego jest podniesiony (x20), a nie zerowy: zablokowano " + limited);
    }

    @Test
    void codeExpires() throws Exception {
        String code = web.linkCodes().create("u1", "Ala", false, System.currentTimeMillis() - 10 * 60_000);
        assertEquals(401, post("/api/login", "{\"code\":\"" + code + "\"}", null).status);
    }

    // ------------------------------------------------------------------ CSRF / walidacja

    @Test
    void crossOriginPostsRejected() throws Exception {
        String ck = login("u1", "Ala", false);
        market.deposit("u1", "Ala", Fixed.parseMoney("1000"));
        String body = "{\"side\":\"buy\",\"coin\":\"MineCoin\",\"qty\":\"1\"}";
        assertEquals(403, post("/api/trade", body, ck, "Origin", "http://evil.example").status);
        assertEquals(200, post("/api/trade", body, ck, "Origin", "http://127.0.0.1:" + web.port()).status);
        assertEquals(0, market.recentTrades(10).stream().filter(t -> t.source().equals("WEB")).count() - 1);
    }

    @Test
    void postRequiresJsonContentType() throws Exception {
        String ck = login("u1", "Ala", false);
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/api/trade"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", ck)
                .POST(HttpRequest.BodyPublishers.ofString("side=buy")).build();
        assertEquals(415, http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void malformedAndOversizedBodies() throws Exception {
        String ck = login("u1", "Ala", false);
        assertEquals(400, post("/api/trade", "not json", ck).status);
        assertEquals(400, post("/api/trade", "[1,2]", ck).status);
        assertEquals(400, post("/api/trade", "{\"side\":\"buy\"}", ck).status);
        assertEquals(413, post("/api/trade", "{\"x\":\"" + "a".repeat(6000) + "\"}", ck).status);
    }

    // ------------------------------------------------------------------ handel

    @Test
    void tradingFlowAndServerSidePricing() throws Exception {
        String ck = login("u1", "Ala", false);
        market.deposit("u1", "Ala", Fixed.parseMoney("5000"));

        // klient próbuje narzucić własną cenę - serwer ignoruje nieznane pola
        Resp buy = post("/api/trade", "{\"side\":\"buy\",\"coin\":\"MineCoin\",\"qty\":\"3\",\"price\":\"0.01\",\"cena_szt\":\"0.01\"}", ck);
        assertEquals(200, buy.status, buy.body);
        long cost = buy.json().get("value").getAsLong();
        assertTrue(cost >= 30_000, "3 szt. po ok. 100 PLN: " + cost);

        JsonObject me = get("/api/me", ck).json();
        assertEquals(500_000 - cost, me.get("balance").getAsLong());
        assertEquals(1, me.getAsJsonArray("holdings").size());
        assertEquals(30_000, me.getAsJsonArray("holdings").get(0).getAsJsonObject().get("qty").getAsLong());

        assertEquals("INSUFFICIENT_HOLDINGS", post("/api/trade", "{\"side\":\"sell\",\"coin\":\"MineCoin\",\"qty\":\"4\"}", ck).json().get("error").getAsString());
        assertEquals(200, post("/api/trade", "{\"side\":\"sell\",\"coin\":\"MineCoin\",\"qty\":\"3\"}", ck).status);

        Resp hist = get("/api/trades?coin=MineCoin&limit=10", ck);
        assertEquals(2, hist.json().getAsJsonArray("trades").size());
    }

    @Test
    void invalidTradeInputs() throws Exception {
        String ck = login("u1", "Ala", false);
        market.deposit("u1", "Ala", Fixed.parseMoney("5000"));
        for (String qty : new String[]{"-1", "0", "abc", "1e3", "NaN", "99999999999999999999", "1.23456", ""}) {
            Resp r = post("/api/trade", "{\"side\":\"buy\",\"coin\":\"MineCoin\",\"qty\":\"" + qty + "\"}", ck);
            assertEquals(400, r.status, "qty=" + qty);
        }
        assertEquals(400, post("/api/trade", "{\"side\":\"buy\",\"coin\":\"Fake\",\"qty\":\"1\"}", ck).status);
        assertEquals(400, post("/api/trade", "{\"side\":\"x\",\"coin\":\"MineCoin\",\"qty\":\"1\"}", ck).status);
        assertEquals(Fixed.parseMoney("5000"), market.account("u1").balance());
    }

    @Test
    void limitOrdersViaApi() throws Exception {
        String ck = login("u1", "Ala", false);
        market.deposit("u1", "Ala", Fixed.parseMoney("5000"));
        Resp o = post("/api/order", "{\"side\":\"buy\",\"coin\":\"MineCoin\",\"qty\":\"2\",\"price\":\"80\"}", ck);
        assertEquals(200, o.status, o.body);
        long id = o.json().get("id").getAsLong();
        JsonObject me = get("/api/me", ck).json();
        assertEquals(1, me.getAsJsonArray("orders").size());
        assertEquals(400, post("/api/order", "{\"side\":\"buy\",\"coin\":\"MineCoin\",\"qty\":\"2\",\"price\":\"-5\"}", ck).status);
        assertEquals(200, post("/api/cancel", "{\"id\":\"" + id + "\"}", ck).status);
        assertEquals(Fixed.parseMoney("5000"), market.account("u1").balance());
        assertEquals(400, post("/api/cancel", "{\"id\":\"" + id + "\"}", ck).status);
        assertEquals(400, post("/api/cancel", "{\"id\":\"abc\"}", ck).status);
    }

    @Test
    void cannotCancelOthersOrders() throws Exception {
        String a = login("u1", "Ala", false);
        String b = login("u2", "Bob", false);
        market.deposit("u1", "Ala", Fixed.parseMoney("5000"));
        long id = post("/api/order", "{\"side\":\"buy\",\"coin\":\"MineCoin\",\"qty\":\"2\",\"price\":\"80\"}", a).json().get("id").getAsLong();
        assertEquals(400, post("/api/cancel", "{\"id\":\"" + id + "\"}", b).status);
        assertEquals(1, market.orders("u1").size());
    }

    @Test
    void withdrawQueuesPayout() throws Exception {
        String ck = login("u1", "Ala", false);
        market.deposit("u1", "Ala", Fixed.parseMoney("500"));
        assertEquals(400, post("/api/withdraw", "{\"amount\":\"0.5\"}", ck).status);
        assertEquals(400, post("/api/withdraw", "{\"amount\":\"501\"}", ck).status);
        Resp r = post("/api/withdraw", "{\"amount\":\"200\"}", ck);
        assertEquals(200, r.status, r.body);
        assertEquals(1, kicks.get());
        assertEquals(Fixed.parseMoney("300"), market.account("u1").balance());
        assertEquals(1, market.payouts(MarketService.PAYOUT_PENDING).size());
    }

    // ------------------------------------------------------------------ admin

    @Test
    void adminEndpointsRequireAdmin() throws Exception {
        String user = login("u1", "Ala", false);
        for (String path : new String[]{"/api/admin/price", "/api/admin/trend", "/api/admin/force", "/api/admin/event", "/api/admin/reset", "/api/admin/player"}) {
            assertEquals(403, post(path, "{}", user).status, path);
            assertEquals(401, post(path, "{}", null).status, path);
        }
        assertEquals(403, get("/api/admin/info", user).status);
        assertEquals(0, forced.get());
    }

    @Test
    void adminActions() throws Exception {
        String ck = login("a1", "Szef", true);
        assertEquals(200, get("/api/admin/info", ck).status);

        assertEquals(200, post("/api/admin/price", "{\"coin\":\"MineCoin\",\"mode\":\"set\",\"value\":\"250\"}", ck).status);
        assertEquals(25_000, market.price("MineCoin"));
        assertEquals(200, post("/api/admin/price", "{\"coin\":\"MineCoin\",\"mode\":\"pump\",\"value\":\"20\"}", ck).status);
        assertEquals(30_000, market.price("MineCoin"));
        assertEquals(200, post("/api/admin/price", "{\"coin\":\"MineCoin\",\"mode\":\"dump\",\"value\":\"50\"}", ck).status);
        assertEquals(15_000, market.price("MineCoin"));
        assertEquals(400, post("/api/admin/price", "{\"coin\":\"MineCoin\",\"mode\":\"dump\",\"value\":\"99\"}", ck).status);
        assertEquals(400, post("/api/admin/price", "{\"coin\":\"MineCoin\",\"mode\":\"set\",\"value\":\"-1\"}", ck).status);

        assertEquals(200, post("/api/admin/trend", "{\"coin\":\"MineCoin\",\"percent\":\"40\"}", ck).status);
        assertEquals(400, post("/api/admin/trend", "{\"coin\":\"MineCoin\",\"percent\":\"abc\"}", ck).status);

        assertEquals(200, post("/api/admin/force", "{}", ck).status);
        assertEquals(1, forced.get());
        assertEquals(200, post("/api/admin/event", "{\"index\":\"1\"}", ck).status);
        assertEquals(List.of(1), ranEvents);
        assertEquals(400, post("/api/admin/event", "{\"index\":\"99\"}", ck).status);

        market.deposit("u9", "Gracz", 100);
        assertEquals(200, post("/api/admin/player", "{\"player\":\"gracz\",\"asset\":\"BALANCE\",\"op\":\"add\",\"value\":\"1500\"}", ck).status);
        assertEquals(150_100, market.account("u9").balance());
        assertEquals(200, post("/api/admin/player", "{\"player\":\"Gracz\",\"asset\":\"CreepDoge\",\"op\":\"set\",\"value\":\"12.5\"}", ck).status);
        assertEquals(125_000, market.holdingQty("u9", "CreepDoge"));
        assertEquals(400, post("/api/admin/player", "{\"player\":\"Nikt\",\"asset\":\"BALANCE\",\"op\":\"add\",\"value\":\"1\"}", ck).status);

        assertEquals(400, post("/api/admin/reset", "{}", ck).status, "wymaga potwierdzenia");
        assertEquals(200, post("/api/admin/reset", "{\"confirm\":\"RESET\"}", ck).status);
        assertEquals(10_000, market.price("MineCoin"));
        assertEquals(150_100, market.account("u9").balance(), "reset bez wipePlayers zachowuje salda");
        assertTrue(audit.size() >= 8, "akcje admina trafiają do logu");
    }

    @Test
    void adminFlagComesOnlyFromLinkCode() throws Exception {
        String ck = login("u1", "Ala", false);
        // próba "podniesienia" uprawnień przez pola w body nic nie daje
        assertEquals(403, post("/api/admin/force", "{\"admin\":true}", ck).status);
    }

    // ------------------------------------------------------------------ własne kryptowaluty i eventy (admin)

    @Test
    void adminCanManageCustomCoins() throws Exception {
        String user = login("u-user", "Zwykly", false);
        String ck = login("u-adm", "Szef", true);
        String coin = "{\"id\":\"ZetaCoin\",\"price\":\"120.50\",\"volMin\":\"0.95\",\"volMax\":\"1.05\",\"liquidity\":\"\",\"icon\":\"emerald\"}";
        assertEquals(403, post("/api/admin/coin/add", coin, user).status);
        assertEquals(401, post("/api/admin/coin/add", coin, null).status);
        Resp r = post("/api/admin/coin/add", coin, ck);
        assertEquals(200, r.status, r.body);
        assertEquals(12_050, market.price("ZetaCoin"));
        // pojawia się w /api/market i w admin/info
        assertTrue(get("/api/market", null).body.contains("ZetaCoin"));
        assertTrue(get("/api/admin/info", ck).body.contains("\"custom\":true"));
        assertEquals("pl", get("/api/market", null).json().get("lang").getAsString());
        // duplikat i złe dane
        assertEquals("EXISTS", post("/api/admin/coin/add", coin, ck).json().get("error").getAsString());
        assertEquals(400, post("/api/admin/coin/add", coin.replace("ZetaCoin", "x"), ck).status);
        assertEquals(400, post("/api/admin/coin/add", coin.replace("ZetaCoin", "Beta").replace("0.95", "abc"), ck).status);
        // edycja
        Resp up = post("/api/admin/coin/update", coin.replace("0.95", "0.9"), ck);
        assertEquals(200, up.status, up.body);
        assertEquals(400, post("/api/admin/coin/update", coin.replace("ZetaCoin", "MineCoin"), ck).status, "waluty z configu nie edytujemy");
        // usuwanie
        assertEquals(400, post("/api/admin/coin/delete", "{\"id\":\"MineCoin\"}", ck).status);
        assertEquals(200, post("/api/admin/coin/delete", "{\"id\":\"ZetaCoin\"}", ck).status);
        assertNull(market.canon("ZetaCoin"));
        assertTrue(audit.stream().anyMatch(a -> a.contains("nowa kryptowaluta ZetaCoin")));
    }

    @Test
    void adminCanManageCustomEvents() throws Exception {
        String user = login("u-user", "Zwykly", false);
        String ck = login("u-adm", "Szef", true);
        String ev = "{\"type\":\"HOSSA\",\"text\":\"Nowy hit\",\"coin\":\"MineCoin\",\"multiplier\":\"1.25\",\"weight\":\"2\",\"color\":\"&a\"}";
        assertEquals(403, post("/api/admin/event/add", ev, user).status);
        assertEquals(200, post("/api/admin/event/add", ev, ck).status);
        assertEquals(1, market.customEvents().size());
        assertEquals(400, post("/api/admin/event/add", ev.replace("MineCoin", "Nope"), ck).status);
        assertEquals(400, post("/api/admin/event/add", ev.replace("1.25", "zero"), ck).status);
        assertEquals(200, post("/api/admin/event/add", "{\"type\":\"INFO\",\"text\":\"Bez waluty\",\"coin\":\"\",\"multiplier\":\"\",\"weight\":\"1\"}", ck).status);
        assertEquals(2, market.customEvents().size());
        long id = market.customEvents().get(0).id();
        assertEquals(200, post("/api/admin/event/delete", "{\"id\":\"" + id + "\"}", ck).status);
        assertEquals(400, post("/api/admin/event/delete", "{\"id\":\"" + id + "\"}", ck).status);
        assertEquals(1, market.customEvents().size());
        Resp imp = post("/api/admin/event/import", "{}", ck);
        assertEquals(200, imp.status);
        assertEquals(0, imp.json().get("added").getAsInt());
    }
}
