package io.github.waleed875.memo.platform.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FixedWindowAttemptLimiterTest {
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-24T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
    }

    @Test void limitsAttemptsByClientAndResetsAfterOneMinute() {
        MutableClock clock = new MutableClock();
        FixedWindowAttemptLimiter limiter = new FixedWindowAttemptLimiter(clock);
        for (int i = 0; i < 10; i++) assertTrue(limiter.acquire("192.0.2.10").allowed());
        var denied = limiter.acquire("192.0.2.10");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() > 0);
        assertTrue(limiter.acquire("192.0.2.11").allowed());
        clock.advance(60);
        assertTrue(limiter.acquire("192.0.2.10").allowed());
    }
}
