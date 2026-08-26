package pl.example.syslogparser.expr;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiltersTest {

    private final Filters filters = new Filters();

    @Test
    void priFacilityAndSeverityDecomposeTheStandardSyslogPriValue() {
        // <187> = facility 23 (local7), severity 3 (error)
        assertThat(apply("pri_facility", "187")).isEqualTo(23);
        assertThat(apply("pri_severity", "187")).isEqualTo(3);
    }

    @Test
    void intParsesToLong() {
        assertThat(apply("int", "402")).isEqualTo(402L);
    }

    @Test
    void lowerUpperTrim() {
        assertThat(apply("lower", "FAILED")).isEqualTo("failed");
        assertThat(apply("upper", "failed")).isEqualTo("FAILED");
        assertThat(apply("trim", "  x  ")).isEqualTo("x");
    }

    @Test
    void eqComparesAgainstItsArgument() {
        assertThat(apply("eq", "up", "up")).isEqualTo(true);
        assertThat(apply("eq", "down", "up")).isEqualTo(false);
    }

    @Test
    void defaultSubstitutesOnlyWhenInputIsNull() {
        assertThat(filters.lookup("default").orElseThrow().fn().apply(null, List.of("n/a"))).isEqualTo("n/a");
        assertThat(filters.lookup("default").orElseThrow().fn().apply("x", List.of("n/a"))).isEqualTo("x");
    }

    @Test
    void parseTsAssumesTheCurrentYearWhenNotInFuture() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
        Filters f = new Filters(fixed);
        Object result = f.lookup("parse_ts").orElseThrow().fn()
                .apply("Aug 26 10:14:22.531", List.of("MMM ppd HH:mm:ss.SSS", "UTC"));
        assertThat(result).isEqualTo(Instant.parse("2026-08-26T10:14:22.531Z"));
    }

    @Test
    void parseTsRollsBackAYearAcrossTheNewYearBoundary() {
        // "now" is Jan 1st; a message timestamped Dec 31 must be last year's,
        // not a message from the future.
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:05:00Z"), ZoneOffset.UTC);
        Filters f = new Filters(fixed);
        Object result = f.lookup("parse_ts").orElseThrow().fn()
                .apply("Dec 31 23:59:59.000", List.of("MMM ppd HH:mm:ss.SSS", "UTC"));
        assertThat(result).isEqualTo(Instant.parse("2025-12-31T23:59:59.000Z"));
    }

    @Test
    void unknownFilterIsAbsentFromTheRegistry() {
        assertThatThrownBy(() -> filters.lookup("pri_facilty").orElseThrow())
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    private Object apply(String filter, String input) {
        return filters.lookup(filter).orElseThrow().fn().apply(input, List.of());
    }

    private Object apply(String filter, String input, String arg) {
        return filters.lookup(filter).orElseThrow().fn().apply(input, List.of(arg));
    }
}
