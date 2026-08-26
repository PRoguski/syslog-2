package pl.example.syslogparser.expr;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

/**
 * Parses Cisco IOS XR timestamps such as {@code Aug 26 10:14:22.531}, which
 * carry no year. The year is assumed to be the current one (per
 * {@code fields.assume_current_year} in {@code service.yaml}), with a
 * rollover check: if that placed the message more than a day in the future,
 * it must actually be from last year — a message logged Dec 31 23:59 and
 * read back on Jan 1.
 */
final class TimestampFilters {

    private TimestampFilters() {
    }

    static Object parse(Object input, List<String> args, Clock clock) {
        if (input == null) {
            return null;
        }
        String pattern = args.get(0);
        String zoneArg = args.size() > 1 ? args.get(1) : null;
        ZoneId zone = (zoneArg == null || zoneArg.isBlank()) ? ZoneId.of("UTC") : parseZone(zoneArg);

        int currentYear = ZonedDateTime.now(clock).withZoneSameInstant(zone).getYear();
        DateTimeFormatter fmt = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .parseDefaulting(ChronoField.YEAR, currentYear)
                .toFormatter(Locale.ENGLISH);

        LocalDateTime parsed = LocalDateTime.parse(((String) input).trim(), fmt);
        ZonedDateTime candidate = parsed.atZone(zone);
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        if (candidate.isAfter(now.plusDays(1))) {
            candidate = parsed.withYear(currentYear - 1).atZone(zone);
        }
        return candidate.toInstant();
    }

    private static ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (RuntimeException e) {
            // Cisco emits short zone abbreviations (UTC, GMT...) that
            // ZoneId mostly understands directly; anything it does not
            // recognise falls back to UTC rather than failing the message.
            return ZoneId.of("UTC");
        }
    }
}
