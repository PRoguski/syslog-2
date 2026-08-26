package pl.example.syslogparser.engine;

import org.junit.jupiter.api.Test;
import pl.example.syslogparser.config.ConfigValidator;
import pl.example.syslogparser.config.RulesConfig;
import pl.example.syslogparser.expr.Filters;
import pl.example.syslogparser.expr.KafkaMeta;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
    private static final KafkaMeta META = new KafkaMeta(3, 100L, Instant.parse("2026-08-26T10:14:23Z"));

    private final ConfigValidator validator = new ConfigValidator(new Filters(CLOCK));

    @Test
    void firstMatchStopsAtTheFirstMatchingRuleInFileOrder() {
        RulesConfig config = new RulesConfig(
                Map.of(),
                new RulesConfig.Defaults(Map.of()),
                List.of(
                        new RulesConfig.RuleDef("specific", true, "AUTHEN_SUCCESS",
                                "^AUTHEN_(?<result>SUCCESS|FAILED)$",
                                new RulesConfig.Output("net-audit-events", null),
                                Map.of("event_type", "login", "result", "{{ result | lower }}")),
                        new RulesConfig.RuleDef("catch_all", true, null,
                                "^(?<anything>.*)$",
                                new RulesConfig.Output("syslog-json", null),
                                Map.of("event_type", "generic"))));

        RuleEngine engine = engineFor(config, RuleEngine.Strategy.FIRST_MATCH);

        List<MatchResult> results = engine.match("AUTHEN_SUCCESS", META);

        assertThat(results).hasSize(1);
        MatchResult.Matched matched = (MatchResult.Matched) results.get(0);
        assertThat(matched.ruleName()).isEqualTo("specific");
        assertThat(matched.topic()).isEqualTo("net-audit-events");
        assertThat(matched.json().get("result").asText()).isEqualTo("success");
    }

    @Test
    void allMatchesStrategyCanFanOutToSeveralTopics() {
        RulesConfig config = new RulesConfig(
                Map.of(),
                new RulesConfig.Defaults(Map.of()),
                List.of(
                        new RulesConfig.RuleDef("a", true, null, "^(?<x>.*)$",
                                new RulesConfig.Output("topic-a", null), Map.of()),
                        new RulesConfig.RuleDef("b", true, null, "^(?<y>.*)$",
                                new RulesConfig.Output("topic-b", null), Map.of())));

        RuleEngine engine = engineFor(config, RuleEngine.Strategy.ALL_MATCHES);

        List<MatchResult> results = engine.match("hello", META);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(MatchResult.Matched.class));
        assertThat(results.stream().map(r -> ((MatchResult.Matched) r).topic()))
                .containsExactlyInAnyOrder("topic-a", "topic-b");
    }

    @Test
    void noRuleMatchesProducesUnmatched() {
        RulesConfig config = new RulesConfig(
                Map.of(), new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef("only", true, null, "^nope$",
                        new RulesConfig.Output("t", null), Map.of())));

        RuleEngine engine = engineFor(config, RuleEngine.Strategy.FIRST_MATCH);
        List<MatchResult> results = engine.match("something else", META);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isInstanceOfSatisfying(MatchResult.Unmatched.class,
                u -> assertThat(u.raw()).isEqualTo("something else"));
    }

    @Test
    void prefilterSkipsTheRegexEntirelyWhenTheSubstringIsAbsent() {
        RulesConfig config = new RulesConfig(
                Map.of(), new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef("needs_marker", true, "MARKER",
                        "^.*$", new RulesConfig.Output("t", null), Map.of())));

        RuleEngine engine = engineFor(config, RuleEngine.Strategy.FIRST_MATCH);
        List<MatchResult> results = engine.match("no marker here", META);

        assertThat(results.get(0)).isInstanceOf(MatchResult.Unmatched.class);
    }

    @Test
    void outputKeyIsRenderedFromAGroupWhenConfigured() {
        RulesConfig config = new RulesConfig(
                Map.of(), new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef("keyed", true, null,
                        "^from (?<srcIp>[\\d.]+)$",
                        new RulesConfig.Output("t", "{{ srcIp }}"), Map.of())));

        RuleEngine engine = engineFor(config, RuleEngine.Strategy.FIRST_MATCH);
        MatchResult.Matched matched = (MatchResult.Matched) engine.match("from 10.0.0.1", META).get(0);

        assertThat(matched.key()).contains("10.0.0.1");
    }

    private RuleEngine engineFor(RulesConfig config, RuleEngine.Strategy strategy) {
        ConfigValidator.Result validated = validator.validateRules(config);
        return new RuleEngine(validated.rules(), strategy, CLOCK);
    }
}
