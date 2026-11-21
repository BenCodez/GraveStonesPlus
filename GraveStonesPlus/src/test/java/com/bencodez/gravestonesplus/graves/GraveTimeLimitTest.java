package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraveTimeLimitTest {
    @Test void preservesLegacyAndSupportedDurationUnits() {
        assertEquals(120_000, GraveTimeLimit.parse("2").getMillis());
        for (String text : List.of("1h30m", "1 H 30 MINUTES", "PT1H30M"))
            assertEquals(5_400_000, GraveTimeLimit.parse(text).getMillis());
        assertEquals(1, GraveTimeLimit.parse("1ms").getMillis());
        assertEquals(604_800_000, GraveTimeLimit.parse("1week").getMillis());
        assertEquals(2_592_000_000L, GraveTimeLimit.parse("1month").getMillis());
        for (String text : List.of("", "  ", "0", "-1", "0m0s", "PT0S"))
            assertEquals(0, GraveTimeLimit.parse(text).getMillis());
        assertEquals(0, GraveTimeLimit.parse(null).getMillis());
    }
    @Test void malformedAndOverflowingInputCannotBecomeDisabledOrSaturated() {
        for (String text : List.of("tomorrow", "2unknown", "1h garbage", "1h,30m", "PTtomorrow",
                "999999999999999999999999999", "9223372036854775807m", "9223372036854775807ms1ms",
                "PT999999999999999999999999999H"))
            assertThrows(RuntimeException.class, () -> GraveTimeLimit.parse(text), text);
    }
}
