package pl.example.syslogparser.streams;

import pl.example.syslogparser.engine.MatchResult;

/**
 * What gets produced for a line that didn't turn into a {@code Matched}
 * result — either nothing matched, or a matched rule's fields failed to
 * render. Sent to {@code routing.on_no_match.topic} for both the {@code dlq}
 * and {@code passthrough} actions (the {@code drop} action never builds one).
 */
public record DlqPayload(String raw, String rule, String reason, boolean parseError, int partition, long offset) {

    static DlqPayload of(MatchResult result) {
        return switch (result) {
            case MatchResult.Unmatched u -> new DlqPayload(
                    u.raw(), null, "no rule matched", true, u.meta().partition(), u.meta().offset());
            case MatchResult.RenderError e -> new DlqPayload(
                    e.raw(), e.ruleName(), "field render error: " + e.cause(), true, e.meta().partition(), e.meta().offset());
            case MatchResult.Matched m -> throw new IllegalArgumentException(
                    "a Matched result never goes to the DLQ");
        };
    }
}
