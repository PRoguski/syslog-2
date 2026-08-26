package pl.example.syslogparser.config;

import org.junit.jupiter.api.Test;
import pl.example.syslogparser.engine.CompiledRule;
import pl.example.syslogparser.expr.Filters;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator(new Filters());

    @Test
    void compilesAValidRuleAndItsFields() {
        RulesConfig config = new RulesConfig(
                Map.of(),
                new RulesConfig.Defaults(Map.of("raw", "{{ raw }}")),
                List.of(new RulesConfig.RuleDef(
                        "xr_generic", true, null,
                        "^%(?<mnemonic>[\\w-]+) : (?<message>.*)$",
                        new RulesConfig.Output("syslog-json", null),
                        Map.of("message", "{{ message }}"))));

        ConfigValidator.Result result = validator.validateRules(config);

        assertThat(result.rules()).hasSize(1);
        CompiledRule rule = result.rules().get(0);
        assertThat(rule.name()).isEqualTo("xr_generic");
        assertThat(rule.fields()).containsKeys("raw", "message");
    }

    @Test
    void unknownGroupInAFieldExpressionFailsWithAllIssuesCollected() {
        RulesConfig config = new RulesConfig(
                Map.of(),
                new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef(
                        "xr_link_updown", true, null,
                        "^Interface (?<state>\\w+)$",
                        new RulesConfig.Output("syslog-json", null),
                        Map.of("interface", "{{ iface }}"))));

        assertThatThrownBy(() -> validator.validateRules(config))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("xr_link_updown")
                .hasMessageContaining("unknown group 'iface'")
                .hasMessageContaining("state");
    }

    @Test
    void duplicateRuleNamesAreRejected() {
        RulesConfig.RuleDef rule = new RulesConfig.RuleDef(
                "dup", true, null, "^a$", new RulesConfig.Output("t", null), Map.of());
        RulesConfig config = new RulesConfig(Map.of(), new RulesConfig.Defaults(Map.of()), List.of(rule, rule));

        assertThatThrownBy(() -> validator.validateRules(config))
                .hasMessageContaining("duplicate rule name");
    }

    @Test
    void definesAreSubstitutedBeforePatternCompile() {
        RulesConfig config = new RulesConfig(
                Map.of("prefix", "^(?<pid>\\d+): "),
                new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef(
                        "with_define", true, null,
                        "{{prefix}}(?<msg>.*)$",
                        new RulesConfig.Output("t", null),
                        Map.of("pid", "{{ pid | int }}", "msg", "{{ msg }}"))));

        ConfigValidator.Result result = validator.validateRules(config);
        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).pattern().matcher("402: hello").matches()).isTrue();
    }

    @Test
    void emptyOutputTopicFailsValidation() {
        RulesConfig config = new RulesConfig(
                Map.of(),
                new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef(
                        "no_topic", true, null, "^a$", new RulesConfig.Output("", null), Map.of())));

        assertThatThrownBy(() -> validator.validateRules(config))
                .hasMessageContaining("output.topic");
    }

    @Test
    void disabledRulesAreSkippedEntirely() {
        RulesConfig config = new RulesConfig(
                Map.of(),
                new RulesConfig.Defaults(Map.of()),
                List.of(new RulesConfig.RuleDef(
                        "disabled", false, null,
                        "{{ this is not even compiled }}",
                        new RulesConfig.Output(null, null), Map.of())));

        ConfigValidator.Result result = validator.validateRules(config);
        assertThat(result.rules()).isEmpty();
    }

    @Test
    void serviceConfigRejectsAnUnknownRoutingStrategy() {
        ServiceConfig cfg = new ServiceConfig(
                new ServiceConfig.KafkaConfig("kafka:9092", "app", new ServiceConfig.InputConfig("in"),
                        new ServiceConfig.StreamsConfig(1, "at_least_once", 1000)),
                new ServiceConfig.RoutingConfig("bogus_strategy", new ServiceConfig.OnNoMatch("drop", null)),
                new ServiceConfig.FieldsConfig(true, "UTC"),
                new ServiceConfig.MetricsConfig(9090),
                new ServiceConfig.HealthConfig(8080, 300));

        assertThatThrownBy(() -> validator.validateService(cfg))
                .hasMessageContaining("routing.strategy");
    }
}
