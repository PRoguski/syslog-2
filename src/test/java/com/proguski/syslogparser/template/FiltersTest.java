package com.proguski.syslogparser.template;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FiltersTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
    private final RenderContext ctx = new RenderContext(Map.of(), clock);

    private Object filter(String name, Object value, Object... args) {
        return Filters.applyFilter(name, value, List.of(args), ctx);
    }

    @Test
    void priSeverityAndFacility() {
        // <187> = facility 23 (local7), severity 3 (error)
        assertEquals(3L, filter("pri_severity", "187"));
        assertEquals(23L, filter("pri_facility", "187"));
        assertEquals(6L, filter("pri_severity", "190"));
        assertEquals(0L, filter("pri_severity", "0"));
        assertEquals(0L, filter("pri_facility", "0"));
    }

    @Test
    void castsAndStringFilters() {
        assertEquals(402L, filter("int", "402"));
        assertEquals(1.5, filter("float", "1.5"));
        assertEquals(true, filter("bool", "true"));
        assertEquals("down", filter("lower", "Down"));
        assertEquals("UP", filter("upper", "up"));
        assertEquals("x", filter("trim", "  x "));
        assertEquals("fallback", filter("default", null, "fallback"));
        assertEquals("value", filter("default", "value", "fallback"));
        assertThrows(TemplateException.class, () -> filter("int", "not-a-number"));
        assertThrows(TemplateException.class, () -> filter("lower", (Object) null));
    }

    @Test
    void parseTsFillsYearFromClock() {
        assertEquals("2026-08-26T10:14:22.531+00:00",
                filter("parse_ts", "Aug 26 10:14:22.531", "%b %d %H:%M:%S.%f", "UTC"));
    }

    @Test
    void parseTsHandlesSingleDigitDayWithDoubleSpace() {
        // IOS XR pads single-digit days: "Aug  5"
        assertEquals("2026-08-05T01:02:03.004+00:00",
                filter("parse_ts", "Aug  5 01:02:03.004", "%b %d %H:%M:%S.%f", "UTC"));
    }

    @Test
    void parseTsRollsBackYearForFutureDates() {
        // Clock is Aug 2026; a December timestamp must land in 2025, not the future.
        assertEquals("2025-12-31T23:59:59.999+00:00",
                filter("parse_ts", "Dec 31 23:59:59.999", "%b %d %H:%M:%S.%f", "UTC"));
    }

    @Test
    void parseTsRejectsGarbage() {
        assertThrows(TemplateException.class,
                () -> filter("parse_ts", "totally wrong", "%b %d %H:%M:%S.%f", "UTC"));
        assertThrows(TemplateException.class,
                () -> filter("parse_ts", "Aug 26 10:14:22.531", "%b %d %H:%M:%S.%f", "NOPE"));
    }

    @Test
    void nowUsesTheFrozenClock() {
        assertEquals("2026-08-26T12:00:00.000+00:00",
                Filters.callFunction("now", List.of(), ctx));
    }
}
