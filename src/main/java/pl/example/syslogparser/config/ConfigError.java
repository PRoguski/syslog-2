package pl.example.syslogparser.config;

import java.util.List;

/**
 * Thrown for any problem with {@code service.yaml} / {@code rules.yaml}.
 * A single instance can carry several {@link Issue}s so the caller can print
 * every problem found in one pass instead of failing on the first one.
 */
public final class ConfigError extends RuntimeException {

    /** One validation problem, scoped to a rule and field when applicable. */
    public record Issue(String rule, String field, String message) {

        public Issue(String message) {
            this(null, null, message);
        }

        @Override
        public String toString() {
            if (rule == null && field == null) {
                return message;
            }
            if (rule == null) {
                return "field \"%s\": %s".formatted(field, message);
            }
            if (field == null) {
                return "rule \"%s\": %s".formatted(rule, message);
            }
            return "rule \"%s\", field \"%s\": %s".formatted(rule, field, message);
        }
    }

    private final List<Issue> issues;

    public ConfigError(String message) {
        this(List.of(new Issue(message)));
    }

    public ConfigError(String message, Throwable cause) {
        super(message, cause);
        this.issues = List.of(new Issue(message));
    }

    public ConfigError(List<Issue> issues) {
        super(renderMessage(issues));
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }

    private static String renderMessage(List<Issue> issues) {
        StringBuilder sb = new StringBuilder("invalid configuration (" + issues.size() + " issue(s)):");
        for (Issue issue : issues) {
            sb.append("\n  ").append(issue);
        }
        return sb.toString();
    }
}
