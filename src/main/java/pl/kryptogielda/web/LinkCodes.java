package pl.kryptogielda.web;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Jednorazowe kody logowania generowane w grze komendą /gielda link. */
public final class LinkCodes {

    public record Info(String owner, String name, boolean admin) {}

    private record Entry(Info info, long expires) {}

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;

    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Entry> codes = new HashMap<>();
    private final long ttlMs;

    public LinkCodes(long ttlMs) { this.ttlMs = ttlMs; }

    /** Generuje nowy kod; poprzedni kod tego gracza przestaje działać. */
    public synchronized String create(String owner, String name, boolean admin, long now) {
        purge(now);
        codes.values().removeIf(e -> e.info().owner().equals(owner));
        String code;
        do {
            StringBuilder sb = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
            code = sb.toString();
        } while (codes.containsKey(code));
        codes.put(code, new Entry(new Info(owner, name, admin), now + ttlMs));
        return code;
    }

    /** Zużywa kod (działa tylko raz). Zwraca null, jeśli kod jest błędny lub wygasł. */
    public synchronized Info consume(String raw, long now) {
        purge(now);
        if (raw == null) return null;
        String code = raw.trim().toUpperCase().replace(" ", "").replace("-", "");
        Entry e = codes.remove(code);
        return e == null ? null : e.info();
    }

    private void purge(long now) {
        for (Iterator<Entry> it = codes.values().iterator(); it.hasNext(); ) {
            if (it.next().expires() < now) it.remove();
        }
    }

    public long ttlMs() { return ttlMs; }
}
