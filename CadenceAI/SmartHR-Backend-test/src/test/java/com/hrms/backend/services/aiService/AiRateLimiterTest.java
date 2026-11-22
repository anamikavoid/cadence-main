package com.hrms.backend.services.aiService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiRateLimiterTest {

    @Test
    void allowsTheConfiguredNumberPerMinuteThenRejects() {
        long[] now = {1_000_000L};
        AiRateLimiter limiter = new AiRateLimiter(3, 1000, () -> now[0]);
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"));
        now[0] += 30_000;
        assertFalse(limiter.tryAcquire("u1"), "still inside the same minute");
        now[0] += 31_000;
        assertTrue(limiter.tryAcquire("u1"), "a minute has passed");
    }

    @Test
    void usersAreCountedSeparately() {
        long[] now = {1_000_000L};
        AiRateLimiter limiter = new AiRateLimiter(1, 1000, () -> now[0]);
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u2"));
    }

    @Test
    void enforcesAnOverallDailyLimit() {
        long[] now = {1_000_000L};
        AiRateLimiter limiter = new AiRateLimiter(1000, 5, () -> now[0]);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("u1"));
            now[0] += 120_000;
        }
        assertFalse(limiter.tryAcquire("u1"), "6th request of the day");
        now[0] += 24L * 60 * 60 * 1000;
        assertTrue(limiter.tryAcquire("u1"), "a day later");
    }
}
