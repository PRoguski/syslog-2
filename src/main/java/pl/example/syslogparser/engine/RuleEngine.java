package pl.example.syslogparser.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.example.syslogparser.expr.Context;
import pl.example.syslogparser.expr.KafkaMeta;
import pl.example.syslogparser.expr.Value;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Matches one raw line against the compiled rule set and renders the JSON
 * output. Stateless besides the immutable rule list — safe to share across
 * Kafka Streams threads.
 */
public final class RuleEngine {

    /** Mirrors {@code routing.strategy} in {@code service.yaml}. */
    public enum Strategy {
        FIRST_MATCH, ALL_MATCHES
    }

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<CompiledRule> rules;
    private final Strategy strategy;
    private final Clock clock;

    public RuleEngine(List<CompiledRule> rules, Strategy strategy, Clock clock) {
        this.rules = List.copyOf(rules);
        this.strategy = strategy;
        this.clock = clock;
    }

    public static Strategy strategyOf(String name) {
        return switch (name) {
            case "first_match" -> Strategy.FIRST_MATCH;
            case "all_matches" -> Strategy.ALL_MATCHES;
            default -> throw new IllegalArgumentException("unknown routing strategy: " + name);
        };
    }

    /**
     * @return never empty: one {@link MatchResult.Unmatched} when nothing
     *         matched, one {@link MatchResult.Matched}/{@link MatchResult.RenderError}
     *         per matching rule otherwise (more than one only under
     *         {@link Strategy#ALL_MATCHES}).
     */
    public List<MatchResult> match(String raw, KafkaMeta meta) {
        List<MatchResult> results = new ArrayList<>();
        for (CompiledRule rule : rules) {
            if (rule.prefilter() != null && !raw.contains(rule.prefilter())) {
                continue;
            }
            Matcher matcher = rule.pattern().matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            results.add(render(rule, matcher, raw, meta));
            if (strategy == Strategy.FIRST_MATCH) {
                break;
            }
        }
        if (results.isEmpty()) {
            results.add(new MatchResult.Unmatched(raw, meta));
        }
        return results;
    }

    private MatchResult render(CompiledRule rule, Matcher matcher, String raw, KafkaMeta meta) {
        Context ctx = Context.of(matcher, rule.pattern().namedGroups().keySet(), raw, meta, clock);
        try {
            ObjectNode json = MAPPER.createObjectNode();
            for (Map.Entry<String, Value> field : rule.fields().entrySet()) {
                putField(json, field.getKey(), field.getValue().eval(ctx));
            }
            Optional<String> key = rule.key() == null
                    ? Optional.empty()
                    : Optional.ofNullable(rule.key().eval(ctx)).map(String::valueOf);
            return new MatchResult.Matched(rule.name(), rule.topic(), key, json);
        } catch (RuntimeException e) {
            // Never let a bad field expression escape as an exception here —
            // that would kill the Streams thread. Route it to the DLQ instead.
            log.warn("rule \"{}\": failed to render fields for a matched message: {}", rule.name(), e.toString());
            return new MatchResult.RenderError(rule.name(), raw, meta, e);
        }
    }

    private static void putField(ObjectNode out, String name, Object v) {
        switch (v) {
            case null -> out.putNull(name);
            case Integer i -> out.put(name, i);
            case Long l -> out.put(name, l);
            case Boolean b -> out.put(name, b);
            case Instant t -> out.put(name, t.toString());
            case String s -> out.put(name, s);
            default -> out.putPOJO(name, v);
        }
    }
}
