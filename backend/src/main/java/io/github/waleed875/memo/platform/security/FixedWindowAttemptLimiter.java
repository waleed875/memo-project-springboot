package io.github.waleed875.memo.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-remote-address fixed-window limiter for login and password changes.
 *
 * Only the servlet remote address is used, never an untrusted X-Forwarded-For
 * header. This preview-only local limiter requires shared storage for multi-
 * instance deployments and trusted proxy configuration behind HTTPS.
 */
public final class FixedWindowAttemptLimiter {
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record Window(Instant beganAt, int attempts) {}
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ADDRESSES = 4096;
    private final Map<String, Window> attempts = new HashMap<>();
    private final Clock clock;

    public FixedWindowAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized Decision acquire(String clientAddress) {
        String key = clientAddress == null || clientAddress.isBlank()
            ? "unknown" : clientAddress;
        Instant now = clock.instant();
        Window existing = attempts.get(key);

        if (existing == null || !now.isBefore(existing.beganAt().plus(WINDOW))) {
            if (existing == null && attempts.size() >= MAX_ADDRESSES) {
                Iterator<Map.Entry<String, Window>> it = attempts.entrySet().iterator();
                while (it.hasNext()) {
                    Window old = it.next().getValue();
                    if (!now.isBefore(old.beganAt().plus(WINDOW))) it.remove();
                }
                if (attempts.size() >= MAX_ADDRESSES) return new Decision(false, 60);
            }
            attempts.put(key, new Window(now, 1));
            return new Decision(true, 0);
        }

        long remaining = Math.max(1, Duration.between(now, existing.beganAt().plus(WINDOW)).toSeconds());
        if (existing.attempts() >= MAX_ATTEMPTS) return new Decision(false, remaining);

        attempts.put(key, new Window(existing.beganAt(), existing.attempts() + 1));
        return new Decision(true, 0);
    }
}
