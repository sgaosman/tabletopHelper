package com.tabletophelper.identity.internal;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts attempts by key, such as an IP address or a username, in fixed windows: the first attempt starts a window, and
 * once a key has made the limit's attempts in it, the rest are refused until it ends. Held in memory, so the counts
 * start again when the server does and each server instance keeps its own.
 */
final class AttemptLimiter {

    // Windows past their end are dropped once this many keys are held, so the map stays small
    private static final int SWEEP_AT = 10_000;

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long start, int count) {
    }

    /**
     * @param limit  the attempts a key may make in a window
     * @param window how long a window lasts
     * @param clock  the time in milliseconds
     */
    AttemptLimiter(int limit, Duration window, LongSupplier clock) {
        if (limit < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("A limit needs at least one attempt in a window longer than zero");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Counts an attempt by the key.
     *
     * @return empty when the attempt is allowed, else how long until the key's window ends
     */
    Optional<Duration> attempt(String key) {
        long now = clock.getAsLong();
        if (windows.size() >= SWEEP_AT) {
            windows.values().removeIf(window -> now - window.start() >= windowMillis);
        }
        Window window = windows.compute(key, (k, old) -> old == null || now - old.start() >= windowMillis
                ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        if (window.count() <= limit) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0, window.start() + windowMillis - now)));
    }

    /** Forgets every count. */
    void clear() {
        windows.clear();
    }
}
