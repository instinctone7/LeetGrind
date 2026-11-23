package com.leetgrind.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public final class Utc {
    private Utc() {}

    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static LocalDateTime sql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
