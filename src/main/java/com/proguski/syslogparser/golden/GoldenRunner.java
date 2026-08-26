package com.proguski.syslogparser.golden;

import com.proguski.syslogparser.config.ConfigException;
import com.proguski.syslogparser.config.RulesConfig;
import com.proguski.syslogparser.config.ServiceConfig;
import com.proguski.syslogparser.config.Yaml;
import com.proguski.syslogparser.engine.Outcome;
import com.proguski.syslogparser.engine.RuleEngine;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Golden tests live next to rules.yaml (tests/golden.yaml) and guard regexes,
 * templates AND rule ordering. expect_json is compared as a SUBSET: listed
 * fields must match exactly, unlisted fields are ignored. expect_rule: null
 * asserts a no-match routed per routing.on_no_match.
 */
public final class GoldenRunner {

    public record Result(int total, int failed, List<String> lines) {
        public boolean ok() {
            return failed == 0;
        }
    }

    public static Result run(ServiceConfig service, RulesConfig rulesConfig, Path testsFile) {
        Map<String, Object> root = Yaml.loadFile(testsFile);

        String clockStr = Yaml.str(root, "clock", null);
        Clock clock = clockStr != null
                ? Clock.fixed(Instant.parse(clockStr), ZoneOffset.UTC)
                : Clock.systemUTC();

        RuleEngine engine = RuleEngine.build(service, rulesConfig, clock);
        engine.onTemplateError((rule, msg) -> { /* reported via expectations, keep output clean */ });

        Object testsNode = root.get("tests");
        if (testsNode == null) {
            throw new ConfigException(testsFile + ": missing \"tests\" list");
        }

        List<String> lines = new ArrayList<>();
        int total = 0;
        int failed = 0;

        int i = 0;
        for (Object item : Yaml.asList(testsNode, "tests")) {
            Map<String, Object> t = Yaml.asMap(item, "tests[" + i + "]");
            String name = Yaml.str(t, "name", "test#" + i);
            String input = Yaml.str(t, "input", "");
            boolean expectMatch = t.get("expect_rule") != null;
            i++;
            total++;

            List<String> problems = new ArrayList<>();
            Outcome outcome = engine.process(input);

            if (!expectMatch) {
                if (outcome instanceof Outcome.Matched m) {
                    problems.add("expected no match, but rule \"" + m.emissions().get(0).rule()
                            + "\" matched");
                } else {
                    String expTopic = Yaml.str(t, "expect_topic", null);
                    String actTopic = service.onNoMatchAction() == ServiceConfig.NoMatchAction.DROP
                            ? null : service.onNoMatchTopic();
                    if (expTopic != null && !expTopic.equals(actTopic)) {
                        problems.add("expected topic " + expTopic + ", on_no_match routes to " + actTopic);
                    }
                }
            } else if (outcome instanceof Outcome.Unmatched u) {
                problems.add("expected rule \"" + t.get("expect_rule") + "\", got no match ("
                        + u.reason() + ")");
            } else {
                Outcome.Emission em = ((Outcome.Matched) outcome).emissions().get(0);
                checkEquals(problems, "rule", t.get("expect_rule"), em.rule());
                if (t.containsKey("expect_topic")) {
                    checkEquals(problems, "topic", t.get("expect_topic"), em.topic());
                }
                if (t.containsKey("expect_key")) {
                    checkEquals(problems, "key", t.get("expect_key"), em.key());
                }
                if (t.get("expect_json") != null) {
                    Map<String, Object> expected = Yaml.asMap(t.get("expect_json"), name + ".expect_json");
                    for (Map.Entry<String, Object> e : expected.entrySet()) {
                        Object actual = em.fields().get(e.getKey());
                        if (!valueEquals(e.getValue(), actual)) {
                            problems.add("json field \"" + e.getKey() + "\": expected "
                                    + repr(e.getValue()) + ", got " + repr(actual));
                        }
                    }
                }
            }

            if (problems.isEmpty()) {
                lines.add("PASS  " + name);
            } else {
                failed++;
                lines.add("FAIL  " + name);
                for (String p : problems) {
                    lines.add("      - " + p);
                }
                lines.add("      input: " + input);
            }
        }
        lines.add("");
        lines.add(failed == 0
                ? "OK: " + total + " golden test(s) passed"
                : "FAILED: " + failed + " of " + total + " golden test(s) failed");
        return new Result(total, failed, lines);
    }

    private static void checkEquals(List<String> problems, String what, Object expected, Object actual) {
        if (!valueEquals(expected, actual)) {
            problems.add(what + ": expected " + repr(expected) + ", got " + repr(actual));
        }
    }

    /** Type-aware comparison: YAML ints vs engine longs, doubles, booleans, strings. */
    static boolean valueEquals(Object expected, Object actual) {
        if (expected instanceof Number ne && actual instanceof Number na) {
            if (isIntegral(ne) && isIntegral(na)) {
                return ne.longValue() == na.longValue();
            }
            return ne.doubleValue() == na.doubleValue();
        }
        return Objects.equals(expected, actual);
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    private static String repr(Object o) {
        if (o == null) return "null";
        String type = o.getClass().getSimpleName().toLowerCase();
        return o instanceof String ? "\"" + o + "\"" : o + " (" + type + ")";
    }
}
