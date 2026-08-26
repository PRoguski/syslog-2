package pl.example.syslogparser.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.example.syslogparser.expr.KafkaMeta;

import java.util.Optional;

/**
 * Outcome of trying to match one raw syslog line against the rule set.
 * Exhaustive over {@code switch} — see {@link pl.example.syslogparser.streams.TopologyBuilder}
 * for how each branch is routed.
 */
public sealed interface MatchResult {

    record Matched(String ruleName, String topic, Optional<String> key, ObjectNode json) implements MatchResult {
    }

    record Unmatched(String raw, KafkaMeta meta) implements MatchResult {
    }

    record RenderError(String ruleName, String raw, KafkaMeta meta, Exception cause) implements MatchResult {
    }
}
