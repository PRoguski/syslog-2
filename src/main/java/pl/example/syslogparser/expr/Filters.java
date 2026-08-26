package pl.example.syslogparser.expr;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Locale.ROOT;

/**
 * Registry of filters available to pipelines. Adding a filter is one call to
 * {@code def(...)} in {@link #register()} — nothing else in the engine needs
 * to change. Filters are stateless and thread-safe; the registry itself
 * carries only the shared {@link Clock} that {@code parse_ts} needs to assume
 * the current year.
 */
public final class Filters {

    private final Map<String, FilterDef> registry = new HashMap<>();
    private final Clock clock;

    public Filters(Clock clock) {
        this.clock = clock;
        register();
    }

    public Filters() {
        this(Clock.systemUTC());
    }

    private void register() {
        def("pri_facility", String.class, Integer.class, 0,
                (in, a) -> Integer.parseInt((String) in) >> 3);
        def("pri_severity", String.class, Integer.class, 0,
                (in, a) -> Integer.parseInt((String) in) & 7);
        def("int", String.class, Long.class, 0,
                (in, a) -> Long.parseLong((String) in));
        def("lower", String.class, String.class, 0,
                (in, a) -> ((String) in).toLowerCase(ROOT));
        def("upper", String.class, String.class, 0,
                (in, a) -> ((String) in).toUpperCase(ROOT));
        def("trim", String.class, String.class, 0,
                (in, a) -> ((String) in).strip());
        def("eq", String.class, Boolean.class, 1,
                (in, a) -> a.get(0).equals(in));
        def("default", Object.class, Object.class, 1,
                (in, a) -> in == null ? a.get(0) : in);
        def("parse_ts", String.class, Instant.class, 2,
                (in, a) -> TimestampFilters.parse(in, a, clock));
    }

    private void def(String name, Class<?> in, Class<?> out, int arity, Filter fn) {
        registry.put(name, new FilterDef(name, in, out, arity, fn));
    }

    public Optional<FilterDef> lookup(String name) {
        return Optional.ofNullable(registry.get(name));
    }
}
