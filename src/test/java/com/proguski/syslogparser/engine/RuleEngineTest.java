package com.proguski.syslogparser.engine;

import com.proguski.syslogparser.config.ConfigException;
import com.proguski.syslogparser.config.RulesConfig;
import com.proguski.syslogparser.config.ServiceConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);

    private static ServiceConfig service(ServiceConfig.RoutingStrategy strategy) {
        return new ServiceConfig("localhost:9092", "in", "g", true,
                strategy, ServiceConfig.NoMatchAction.DLQ, "dlq", 9090);
    }

    private static RulesConfig.RuleConfig rule(String name, String regex, String topic,
                                               Map<String, Object> template) {
        return new RulesConfig.RuleConfig(name, true, null, regex, topic, null, template);
    }

    @Test
    void firstMatchRespectsRuleOrder() {
        RulesConfig rules = new RulesConfig(Map.of(), Map.of(), List.of(
                rule("specific", "^ERR (?P<code>\\d+)$", "topic-a",
                        Map.of("code", "{{ code | int }}")),
                rule("catch_all", "^(?P<message>.*)$", "topic-b",
                        Map.of("message", "{{ message }}"))));
        RuleEngine engine = RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH), rules, CLOCK);

        Outcome outcome = engine.process("ERR 42");
        Outcome.Emission em = ((Outcome.Matched) outcome).emissions().get(0);
        assertEquals("specific", em.rule());
        assertEquals("topic-a", em.topic());
        assertEquals(42L, em.fields().get("code"));

        Outcome.Emission generic = ((Outcome.Matched) engine.process("hello")).emissions().get(0);
        assertEquals("catch_all", generic.rule());
    }

    @Test
    void allMatchesCanEmitToSeveralTopics() {
        RulesConfig rules = new RulesConfig(Map.of(), Map.of(), List.of(
                rule("a", "^ERR (?P<code>\\d+)$", "topic-a", Map.of("v", "a")),
                rule("b", "^ERR .*$", "topic-b", Map.of("v", "b"))));
        RuleEngine engine = RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.ALL_MATCHES), rules, CLOCK);

        List<Outcome.Emission> emissions = ((Outcome.Matched) engine.process("ERR 1")).emissions();
        assertEquals(2, emissions.size());
    }

    @Test
    void definesAreSubstitutedBeforeCompilation() {
        RulesConfig rules = new RulesConfig(
                Map.of("prefix", "^<(?P<pri>\\d+)>"),
                Map.of("severity", "{{ pri | pri_severity }}"),
                List.of(rule("r", "{{prefix}}(?P<message>.*)$", "t",
                        Map.of("message", "{{ message }}"))));
        RuleEngine engine = RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH), rules, CLOCK);

        Outcome.Emission em = ((Outcome.Matched) engine.process("<187>boom")).emissions().get(0);
        assertEquals(3L, em.fields().get("severity")); // inherited from defaults
        assertEquals("boom", em.fields().get("message"));
    }

    @Test
    void templateErrorCountsAsNoMatch() {
        RulesConfig rules = new RulesConfig(Map.of(), Map.of(), List.of(
                rule("bad_cast", "^(?P<word>\\w+)$", "t", Map.of("n", "{{ word | int }}"))));
        RuleEngine engine = RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH), rules, CLOCK);
        engine.onTemplateError((r, m) -> {});

        assertInstanceOf(Outcome.Unmatched.class, engine.process("hello"));
        assertInstanceOf(Outcome.Matched.class, engine.process("123"));
    }

    @Test
    void validationFailsFastWithReadableMessages() {
        // template uses a group the regex does not define
        ConfigException e1 = assertThrows(ConfigException.class, () -> RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH),
                new RulesConfig(Map.of(), Map.of(), List.of(
                        rule("r", "^(?P<interface>\\S+)$", "t", Map.of("x", "{{ iface }}")))),
                CLOCK));
        assertTrue(e1.getMessage().contains(
                "rule \"r\": template uses group \"iface\" not present in regex"));

        // duplicate names
        assertThrows(ConfigException.class, () -> RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH),
                new RulesConfig(Map.of(), Map.of(), List.of(
                        rule("r", "^a$", "t", Map.of()),
                        rule("r", "^b$", "t", Map.of()))),
                CLOCK));

        // regex does not compile
        assertThrows(ConfigException.class, () -> RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH),
                new RulesConfig(Map.of(), Map.of(), List.of(
                        rule("r", "^(?P<broken>[$", "t", Map.of()))),
                CLOCK));

        // unknown define
        assertThrows(ConfigException.class, () -> RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH),
                new RulesConfig(Map.of(), Map.of(), List.of(
                        rule("r", "{{missing}}x$", "t", Map.of()))),
                CLOCK));

        // unknown filter
        ConfigException e2 = assertThrows(ConfigException.class, () -> RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH),
                new RulesConfig(Map.of(), Map.of(), List.of(
                        rule("r", "^(?P<x>.)$", "t", Map.of("x", "{{ x | frobnicate }}")))),
                CLOCK));
        assertTrue(e2.getMessage().contains("unknown filter \"frobnicate\""));

        // wrong argument count
        assertThrows(ConfigException.class, () -> RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH),
                new RulesConfig(Map.of(), Map.of(), List.of(
                        rule("r", "^(?P<x>.)$", "t", Map.of("x", "{{ x | default }}")))),
                CLOCK));
    }

    @Test
    void disabledRulesAreSkipped() {
        RulesConfig rules = new RulesConfig(Map.of(), Map.of(), List.of(
                new RulesConfig.RuleConfig("off", false, null, "^(?P<m>.*)$", "t", null,
                        Map.of("m", "{{ m }}")),
                rule("on", "^(?P<m>.*)$", "u", Map.of("m", "{{ m }}"))));
        RuleEngine engine = RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH), rules, CLOCK);
        assertEquals("on", ((Outcome.Matched) engine.process("x")).emissions().get(0).rule());
    }

    @Test
    void ruleTemplateOverridesDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("source", "default-source");
        defaults.put("raw", "{{ raw }}");
        RulesConfig rules = new RulesConfig(Map.of(), defaults, List.of(
                rule("r", "^(?P<m>.*)$", "t", Map.of("source", "override"))));
        RuleEngine engine = RuleEngine.build(
                service(ServiceConfig.RoutingStrategy.FIRST_MATCH), rules, CLOCK);

        Map<String, Object> fields =
                ((Outcome.Matched) engine.process("line")).emissions().get(0).fields();
        assertEquals("override", fields.get("source"));
        assertEquals("line", fields.get("raw"));
    }
}
