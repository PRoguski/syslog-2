package pl.example.syslogparser.expr;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Per-message evaluation context: the values captured by the rule's named
 * regex groups, plus the handful of builtins ({@code raw}, {@code kafka.*})
 * and a {@link Clock} so {@code now()} and year-assumption in {@code parse_ts}
 * are deterministic in tests.
 */
public final class Context {

    private final Map<String, String> groups;
    private final String raw;
    private final KafkaMeta kafka;
    private final Clock clock;

    private Context(Map<String, String> groups, String raw, KafkaMeta kafka, Clock clock) {
        this.groups = groups;
        this.raw = raw;
        this.kafka = kafka;
        this.clock = clock;
    }

    public static Context of(Matcher matcher, Iterable<String> groupNames, String raw, KafkaMeta kafka, Clock clock) {
        Map<String, String> values = new HashMap<>();
        for (String name : groupNames) {
            values.put(name, matcher.group(name));
        }
        return new Context(values, raw, kafka, clock);
    }

    public Object resolve(Source source) {
        return switch (source) {
            case Source.Group g -> groups.get(g.name());
            case Source.Builtin b -> resolveBuiltin(b.name());
            case Source.Call c -> resolveCall(c.name());
        };
    }

    private Object resolveBuiltin(String name) {
        return switch (name) {
            case "raw" -> raw;
            case "kafka.partition" -> kafka.partition();
            case "kafka.offset" -> kafka.offset();
            case "kafka.timestamp" -> kafka.timestamp();
            default -> throw new IllegalStateException("unknown builtin source: " + name);
        };
    }

    private Object resolveCall(String name) {
        return switch (name) {
            case "now" -> Instant.now(clock);
            default -> throw new IllegalStateException("unknown function: " + name + "()");
        };
    }
}
