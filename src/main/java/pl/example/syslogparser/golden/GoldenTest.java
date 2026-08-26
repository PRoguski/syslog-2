package pl.example.syslogparser.golden;

import java.util.Map;

/**
 * One entry in {@code tests/golden.yaml}: a raw input line plus the outcome
 * it must produce. Only the fields listed in {@code expect.fields} are
 * checked — a subset of the JSON output, not the whole document, so a test
 * doesn't have to enumerate defaults like {@code received_at} that change
 * every run.
 */
public record GoldenTest(String name, String input, Expect expect) {

    public record Expect(String rule, String topic, String key, Boolean unmatched, Map<String, Object> fields) {

        public Expect {
            if (fields == null) {
                fields = Map.of();
            }
            if (unmatched == null) {
                unmatched = Boolean.FALSE;
            }
        }

        public boolean isUnmatched() {
            return Boolean.TRUE.equals(unmatched);
        }
    }
}
