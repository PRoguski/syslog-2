package pl.example.syslogparser.engine;

import pl.example.syslogparser.expr.Value;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One rule, fully compiled and validated: the {@link Pattern} and every field
 * {@link Value} are built once at startup and never change — no state is
 * shared between the Kafka Streams threads that will call
 * {@link RuleEngine#match}.
 */
public record CompiledRule(
        String name,
        Pattern pattern,
        String prefilter,
        String topic,
        Value key,
        Map<String, Value> fields) {
}
