package com.proguski.syslogparser.template;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in template filters and functions. The registry doubles as the
 * validation source: unknown names or wrong argument counts are rejected at
 * start-up (fail-fast), wrong values fail at render time (TemplateException).
 */
public final class Filters {

    private Filters() {}

    public static final DateTimeFormatter ISO_MILLIS_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT);

    /** filter name -> {min args, max args} (arguments after the piped value). */
    public static final Map<String, int[]> FILTER_ARITY = Map.ofEntries(
            Map.entry("int", new int[]{0, 0}),
            Map.entry("float", new int[]{0, 0}),
            Map.entry("bool", new int[]{0, 0}),
            Map.entry("lower", new int[]{0, 0}),
            Map.entry("upper", new int[]{0, 0}),
            Map.entry("trim", new int[]{0, 0}),
            Map.entry("default", new int[]{1, 1}),
            Map.entry("pri_severity", new int[]{0, 0}),
            Map.entry("pri_facility", new int[]{0, 0}),
            Map.entry("parse_ts", new int[]{1, 2}));

    /** function name -> {min args, max args}. */
    public static final Map<String, int[]> FUNCTION_ARITY = Map.of(
            "now", new int[]{0, 0});

    public static Object callFunction(String name, List<Object> args, RenderContext ctx) {
        if ("now".equals(name)) {
            return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC)
                    .format(ISO_MILLIS_OFFSET);
        }
        throw new TemplateException("unknown function: " + name);
    }

    public static Object applyFilter(String name, Object value, List<Object> args, RenderContext ctx) {
        switch (name) {
            case "default":
                return value == null ? args.get(0) : value;
            case "int":
                return toLong(value, name);
            case "float":
                if (value instanceof Number n) return n.doubleValue();
                try {
                    return Double.parseDouble(requireString(value, name));
                } catch (NumberFormatException e) {
                    throw new TemplateException("float: not a number: " + value);
                }
            case "bool": {
                if (value instanceof Boolean b) return b;
                String s = requireString(value, name).trim().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("1") || s.equals("yes")) return Boolean.TRUE;
                if (s.equals("false") || s.equals("0") || s.equals("no")) return Boolean.FALSE;
                throw new TemplateException("bool: not a boolean: " + value);
            }
            case "lower":
                return requireString(value, name).toLowerCase(Locale.ROOT);
            case "upper":
                return requireString(value, name).toUpperCase(Locale.ROOT);
            case "trim":
                return requireString(value, name).trim();
            case "pri_severity":
                return toLong(value, name) & 7L;
            case "pri_facility":
                return toLong(value, name) >> 3;
            case "parse_ts": {
                String fmt = String.valueOf(args.get(0));
                String tz = args.size() > 1 && args.get(1) != null ? String.valueOf(args.get(1)) : "UTC";
                return parseTs(requireString(value, name), fmt, tz, ctx.clock());
            }
            default:
                throw new TemplateException("unknown filter: " + name);
        }
    }

    private static String requireString(Object value, String filter) {
        if (value == null) {
            throw new TemplateException(filter + ": value is null (use | default(...) for optional groups)");
        }
        return String.valueOf(value);
    }

    private static long toLong(Object value, String filter) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(requireString(value, filter).trim());
        } catch (NumberFormatException e) {
            throw new TemplateException(filter + ": not an integer: " + value);
        }
    }

    // ------------------------------------------------------------- parse_ts

    private static final List<String> MONTHS = List.of(
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec");

    /**
     * strptime-style parser for device timestamps. Supported directives:
     * %b %d %H %M %S %f %Y %m. IOS XR timestamps carry no year — it is taken
     * from the (possibly frozen) clock, stepping one year back when the result
     * would land in the future (New Year's Eve edge).
     */
    static String parseTs(String value, String fmt, String tzName, Clock clock) {
        ZoneId zone = resolveZone(tzName);
        int year = -1, month = -1, day = -1, hour = 0, minute = 0, second = 0, nanos = 0;

        int vi = 0;
        int fi = 0;
        try {
            while (fi < fmt.length()) {
                char f = fmt.charAt(fi);
                if (f == '%' && fi + 1 < fmt.length()) {
                    char d = fmt.charAt(fi + 1);
                    fi += 2;
                    switch (d) {
                        case 'b' -> {
                            String abbr = value.substring(vi, vi + 3).toLowerCase(Locale.ROOT);
                            month = MONTHS.indexOf(abbr) + 1;
                            if (month == 0) throw new TemplateException("parse_ts: unknown month \"" + abbr + "\"");
                            vi += 3;
                        }
                        case 'd' -> { int[] r = readInt(value, vi, 2); day = r[0]; vi = r[1]; }
                        case 'm' -> { int[] r = readInt(value, vi, 2); month = r[0]; vi = r[1]; }
                        case 'H' -> { int[] r = readInt(value, vi, 2); hour = r[0]; vi = r[1]; }
                        case 'M' -> { int[] r = readInt(value, vi, 2); minute = r[0]; vi = r[1]; }
                        case 'S' -> { int[] r = readInt(value, vi, 2); second = r[0]; vi = r[1]; }
                        case 'Y' -> { int[] r = readInt(value, vi, 4); year = r[0]; vi = r[1]; }
                        case 'f' -> {
                            int start = vi;
                            while (vi < value.length() && vi - start < 9
                                    && Character.isDigit(value.charAt(vi))) vi++;
                            if (vi == start) throw new TemplateException("parse_ts: expected fraction digits");
                            String frac = value.substring(start, vi);
                            nanos = Integer.parseInt(frac) * (int) Math.pow(10, 9 - frac.length());
                        }
                        case '%' -> {
                            if (value.charAt(vi) != '%') throw new TemplateException("parse_ts: expected '%'");
                            vi++;
                        }
                        default -> throw new TemplateException("parse_ts: unsupported directive %" + d);
                    }
                } else if (f == ' ') {
                    // like strptime: a space in the format matches a run of spaces
                    fi++;
                    if (vi >= value.length() || value.charAt(vi) != ' ') {
                        throw new TemplateException("parse_ts: expected space");
                    }
                    while (vi < value.length() && value.charAt(vi) == ' ') vi++;
                } else {
                    if (vi >= value.length() || value.charAt(vi) != f) {
                        throw new TemplateException("parse_ts: expected '" + f + "'");
                    }
                    fi++;
                    vi++;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new TemplateException(
                    "parse_ts: value \"" + value + "\" does not match format \"" + fmt + "\"");
        }
        if (vi != value.length()) {
            throw new TemplateException(
                    "parse_ts: trailing content in \"" + value + "\" for format \"" + fmt + "\"");
        }
        if (month < 1 || day < 1) {
            throw new TemplateException("parse_ts: format \"" + fmt + "\" yields no complete date");
        }

        if (year < 0) {
            year = ZonedDateTime.ofInstant(clock.instant(), zone).getYear();
        }
        ZonedDateTime zdt = buildZdt(year, month, day, hour, minute, second, nanos, zone);
        // Device sends no year: a "future" timestamp (beyond small clock skew)
        // means the message is from the previous year.
        if (zdt.toInstant().isAfter(clock.instant().plusSeconds(3 * 24 * 3600))) {
            zdt = buildZdt(year - 1, month, day, hour, minute, second, nanos, zone);
        }
        return zdt.toOffsetDateTime().format(ISO_MILLIS_OFFSET);
    }

    private static ZonedDateTime buildZdt(int year, int month, int day,
                                          int hour, int minute, int second, int nanos, ZoneId zone) {
        try {
            return LocalDate.of(year, month, day)
                    .atTime(hour, minute, second, nanos)
                    .atZone(zone);
        } catch (java.time.DateTimeException e) {
            throw new TemplateException("parse_ts: invalid date/time: " + e.getMessage());
        }
    }

    private static int[] readInt(String value, int from, int maxDigits) {
        int i = from;
        while (i < value.length() && value.charAt(i) == ' ') i++; // "%d" tolerates padding
        int start = i;
        while (i < value.length() && i - start < maxDigits && Character.isDigit(value.charAt(i))) i++;
        if (i == start) {
            throw new TemplateException("parse_ts: expected digits at offset " + from + " in \"" + value + "\"");
        }
        return new int[]{Integer.parseInt(value.substring(start, i)), i};
    }

    static ZoneId resolveZone(String tzName) {
        try {
            return ZoneId.of(tzName, ZoneId.SHORT_IDS);
        } catch (Exception e) {
            throw new TemplateException("parse_ts: unknown time zone \"" + tzName + "\"");
        }
    }
}
