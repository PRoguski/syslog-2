package pl.example.syslogparser.config;

import java.util.List;
import java.util.Map;

/**
 * Root of {@code rules.yaml} — the part of the configuration that changes
 * often and gets its own review in the repository.
 */
public record RulesConfig(
        Map<String, String> defines,
        Defaults defaults,
        List<RuleDef> rules) {

    public RulesConfig {
        if (defines == null) {
            defines = Map.of();
        }
        if (defaults == null) {
            defaults = new Defaults(Map.of());
        }
        if (rules == null) {
            rules = List.of();
        }
    }

    public record Defaults(Map<String, String> fields) {
        public Defaults {
            if (fields == null) {
                fields = Map.of();
            }
        }
    }

    /**
     * A single rule: regex to try, fields to extract, topic to send to.
     * {@code enabled} defaults to {@code true} when absent from YAML.
     */
    public record RuleDef(
            String name,
            Boolean enabled,
            String prefilter,
            String regex,
            Output output,
            Map<String, String> fields) {

        public RuleDef {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (fields == null) {
                fields = Map.of();
            }
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }

    public record Output(String topic, String key) {
    }
}
