package com.leetgrind.platform;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

public final class FixedWindowRateLimiter {
    private final Clock clock;
    private final int maximum;
    private final Map<String, Integer> attempts = new HashMap<>();
    private long window = Long.MIN_VALUE;

    public FixedWindowRateLimiter(Clock clock, int maximum) {
        this.clock = clock;
        this.maximum = maximum;
    }

    public synchronized boolean allow(String key) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (minute != window) {
            attempts.clear();
            window = minute;
        }
        Integer count = attempts.get(key);
        if (count == null) {
            if (attempts.size() >= 4096) return false;
            count = 0;
        }
        if (count >= maximum) return false;
        attempts.put(key, count + 1);
        return true;
    }
}
