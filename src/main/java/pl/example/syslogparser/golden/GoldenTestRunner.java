package pl.example.syslogparser.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import pl.example.syslogparser.engine.MatchResult;
import pl.example.syslogparser.engine.RuleEngine;
import pl.example.syslogparser.expr.KafkaMeta;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Runs {@code tests/golden.yaml} against a {@link RuleEngine}. Shared by the
 * {@code test} CLI subcommand and the JUnit {@code @TestFactory} — one source
 * of truth for "does this raw line still produce what we expect", which is
 * what actually guards regexes, field definitions and rule order.
 */
public final class GoldenTestRunner {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final RuleEngine engine;

    public GoldenTestRunner(RuleEngine engine) {
        this.engine = engine;
    }

    public static List<GoldenTest> load(Path path) {
        try {
            return YAML.readValue(path.toFile(), GoldenSuite.class).tests();
        } catch (IOException e) {
            throw new RuntimeException("cannot load golden tests from " + path + ": " + e.getMessage(), e);
        }
    }

    public void assertPasses(GoldenTest test) {
        KafkaMeta meta = new KafkaMeta(0, 0L, Instant.now());
        List<MatchResult> results = engine.match(test.input(), meta);
        MatchResult first = results.get(0);
        GoldenTest.Expect expect = test.expect();

        if (expect.isUnmatched()) {
            if (!(first instanceof MatchResult.Unmatched)) {
                throw failure(test, "expected no rule to match, but got: " + describe(first));
            }
            return;
        }

        if (!(first instanceof MatchResult.Matched matched)) {
            throw failure(test, "expected rule '" + expect.rule() + "' to match, but got: " + describe(first));
        }
        if (!matched.ruleName().equals(expect.rule())) {
            throw failure(test, "expected rule '" + expect.rule() + "' but matched '" + matched.ruleName() + "'");
        }
        if (expect.topic() != null && !matched.topic().equals(expect.topic())) {
            throw failure(test, "expected topic '" + expect.topic() + "' but got '" + matched.topic() + "'");
        }
        if (expect.key() != null) {
            String actualKey = matched.key().orElse(null);
            if (!expect.key().equals(actualKey)) {
                throw failure(test, "expected key '" + expect.key() + "' but got '" + actualKey + "'");
            }
        }
        for (var e : expect.fields().entrySet()) {
            JsonNode actual = matched.json().get(e.getKey());
            if (actual == null) {
                throw failure(test, "expected field '" + e.getKey() + "' is missing from the output");
            }
            if (!matches(actual, e.getValue())) {
                throw failure(test, "field '" + e.getKey() + "': expected " + e.getValue() + " but got " + actual);
            }
        }
    }

    private static boolean matches(JsonNode actual, Object expected) {
        return switch (expected) {
            case null -> actual.isNull();
            case Number n -> actual.isNumber()
                    && actual.decimalValue().compareTo(new BigDecimal(n.toString())) == 0;
            case Boolean b -> actual.isBoolean() && actual.asBoolean() == b;
            default -> actual.isTextual() && actual.asText().equals(String.valueOf(expected));
        };
    }

    private static String describe(MatchResult r) {
        return switch (r) {
            case MatchResult.Matched m -> "matched '" + m.ruleName() + "' -> " + m.topic();
            case MatchResult.Unmatched u -> "unmatched";
            case MatchResult.RenderError e -> "render error in '" + e.ruleName() + "': " + e.cause();
        };
    }

    private static AssertionError failure(GoldenTest test, String message) {
        return new AssertionError("golden test \"" + test.name() + "\" failed: " + message);
    }
}
