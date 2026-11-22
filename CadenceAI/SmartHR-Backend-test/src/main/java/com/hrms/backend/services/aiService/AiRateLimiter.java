package com.hrms.backend.services.aiService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Abuse / cost protection: limits how many AI requests ONE user can make
 * (default: 10 per minute and 300 per day). Every Gemini call costs money or free-quota.
 *
 * How it works: for each user we remember the times of their recent requests ("sliding window").
 * LIMITATION: the counters live in this server's memory, so they reset on restart and are not
 * shared between several server instances. Fine for this app; use Redis or a gateway if you scale out.
 */
@Component
public class AiRateLimiter {
    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final int perMinute;
    private final int perDay;
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Autowired
    public AiRateLimiter(@Value("${ai.rate-limit.per-minute:10}") int perMinute,
                         @Value("${ai.rate-limit.per-day:300}") int perDay) {
        this(perMinute, perDay, System::currentTimeMillis);
    }

    /** Test constructor: lets tests control the clock. */
    AiRateLimiter(int perMinute, int perDay, LongSupplier nowMillis) {
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.nowMillis = nowMillis;
    }

    /** Returns true and records the request if the user is within limits; false if they must wait. */
    public boolean tryAcquire(String userId) {
        long now = nowMillis.getAsLong();
        Deque<Long> times = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > DAY_MS) {
                times.pollFirst();
            }
            if (times.size() >= perDay) return false;
            long lastMinute = 0;
            for (long t : times) {
                if (now - t <= MINUTE_MS) lastMinute++;
            }
            if (lastMinute >= perMinute) return false;
            times.addLast(now);
            return true;
        }
    }
}
