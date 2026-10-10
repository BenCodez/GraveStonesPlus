package com.bencodez.gravestonesplus.graves;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import com.bencodez.simpleapi.time.ParsedDuration;

/** Validates replacement lifetimes before changing an existing grave timer. */
final class GraveTimeLimit {
    private static final Pattern SEGMENT = Pattern.compile("([0-9]+)\\s*([a-z]+)");
    private static final Set<String> UNITS = Set.of("ms", "msec", "msecs", "millisecond", "milliseconds",
            "s", "sec", "secs", "second", "seconds", "m", "min", "mins", "minute", "minutes",
            "h", "hr", "hrs", "hour", "hours", "d", "day", "days", "w", "wk", "wks", "week", "weeks",
            "mo", "mon", "mons", "month", "months");
    private GraveTimeLimit() { }
    static ParsedDuration parse(String raw) {
        if (raw == null || raw.isBlank()) return ParsedDuration.empty();
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.matches("[+-]?[0-9]+")) {
            long minutes = Long.parseLong(text);
            return ParsedDuration.ofMillis(minutes <= 0 ? 0 : Math.multiplyExact(minutes, 60_000L));
        }
        if (text.startsWith("p")) {
            return ParsedDuration.ofMillis(Duration.parse(text.toUpperCase(Locale.ROOT)).toMillis());
        }
        var tokens = SEGMENT.matcher(text);
        int end = 0; long millis = 0;
        while (tokens.find()) {
            if (!text.substring(end, tokens.start()).isBlank() || !UNITS.contains(tokens.group(2)))
                throw new IllegalArgumentException("Invalid grave lifetime");
            long value = Long.parseLong(tokens.group(1));
            long unit = ParsedDuration.parse("1" + tokens.group(2), TimeUnit.MINUTES).getMillis();
            millis = Math.addExact(millis, Math.multiplyExact(value, unit));
            end = tokens.end();
        }
        if (end == 0 || !text.substring(end).isBlank()) throw new IllegalArgumentException("Invalid grave lifetime");
        return ParsedDuration.ofMillis(millis);
    }
}
