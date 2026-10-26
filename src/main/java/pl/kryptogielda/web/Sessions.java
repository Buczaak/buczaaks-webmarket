package pl.kryptogielda.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Sesje przeglądarkowe w pamięci (tracone przy restarcie serwera - gracz loguje się ponownie kodem). */
public final class Sessions {

    public static final class Session {
        public final String token;
        public final String owner;
        public final String name;
        public final boolean admin;
        final long created;
        volatile long expires;

        Session(String token, String owner, String name, boolean admin, long created, long expires) {
            this.token = token;
            this.owner = owner;
            this.name = name;
            this.admin = admin;
            this.created = created;
            this.expires = expires;
        }
    }

    private static final int MAX_PER_OWNER = 5;

    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Session> map = new HashMap<>();
    private final long ttlMs;

    public Sessions(long ttlMs) { this.ttlMs = ttlMs; }

    public long ttlMs() { return ttlMs; }

    public synchronized Session create(String owner, String name, boolean admin, long now) {
        purge(now);
        // limit sesji na gracza - najstarsze wypadają
        long count = map.values().stream().filter(s -> s.owner.equals(owner)).count();
        while (count >= MAX_PER_OWNER) {
            map.values().stream().filter(s -> s.owner.equals(owner)).min(Comparator.comparingLong(s -> s.created))
                    .ifPresent(s -> map.remove(s.token));
            count--;
        }
        byte[] b = new byte[32];
        rnd.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Session s = new Session(token, owner, name, admin, now, now + ttlMs);
        map.put(token, s);
        return s;
    }

    /** Zwraca sesję i przedłuża jej ważność (okno przesuwne). */
    public synchronized Session get(String token, long now) {
        if (token == null || token.length() > 100) return null;
        Session s = map.get(token);
        if (s == null) return null;
        if (s.expires < now) { map.remove(token); return null; }
        s.expires = now + ttlMs;
        return s;
    }

    public synchronized void remove(String token) { if (token != null) map.remove(token); }

    public synchronized void removeAll(String owner) { map.values().removeIf(s -> s.owner.equals(owner)); }

    public synchronized void purge(long now) {
        for (Iterator<Session> it = map.values().iterator(); it.hasNext(); ) {
            if (it.next().expires < now) it.remove();
        }
    }

    public synchronized int size() { return map.size(); }
}
