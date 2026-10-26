package pl.kryptogielda.web;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Prosty limiter z oknem przesuwnym: maks. N zdarzeń w oknie dla danego klucza. */
public final class RateLimiter {

    private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();
    private long lastCleanup;

    public synchronized boolean allow(String key, int max, long windowMs, long now) {
        if (now - lastCleanup > 60_000) cleanup(now);
        ArrayDeque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst() <= now - windowMs) q.pollFirst();
        if (q.size() >= max) return false;
        q.addLast(now);
        return true;
    }

    private void cleanup(long now) {
        lastCleanup = now;
        for (Iterator<ArrayDeque<Long>> it = hits.values().iterator(); it.hasNext(); ) {
            ArrayDeque<Long> q = it.next();
            while (!q.isEmpty() && q.peekFirst() <= now - 600_000) q.pollFirst();
            if (q.isEmpty()) it.remove();
        }
    }
}
