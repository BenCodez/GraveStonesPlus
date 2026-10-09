package com.bencodez.gravestonesplus.graves;

import java.util.concurrent.TimeUnit;
import com.bencodez.simpleapi.time.ParsedDuration;

/** Same legacy numeric minutes and duration semantics used by expiration. */
public final class GraveDeadlines {
    private GraveDeadlines() { }
    public static long expiry(long death, String configured) {
        if (configured == null || configured.trim().isEmpty()) return 0;
        try {
            long duration = configured.trim().matches("\\d+")
                    ? Math.multiplyExact(Long.parseLong(configured.trim()), 60_000L)
                    : ParsedDuration.parse(configured.trim(), TimeUnit.MINUTES).getMillis();
            return deadline(death, duration);
        } catch (RuntimeException invalid) { return 0; }
    }
    public static long deadline(long death, long duration) {
        if (duration <= 0) return 0;
        try { return Math.addExact(death, duration); } catch (ArithmeticException overflow) { return 0; }
    }
    public static boolean eligible(long now, long deadline, long threshold) {
        return threshold > 0 && deadline > now && deadline - now <= threshold;
    }
}
