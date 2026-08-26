package pl.example.syslogparser.streams;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.example.syslogparser.config.ConfigLoader;
import pl.example.syslogparser.config.ConfigValidator;
import pl.example.syslogparser.config.RulesConfig;
import pl.example.syslogparser.config.ServiceConfig;
import pl.example.syslogparser.engine.RuleEngine;
import pl.example.syslogparser.expr.Filters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real topology end to end with {@link TopologyTestDriver} —
 * no broker involved — against {@code config/rules.yaml}: routing to a
 * per-rule topic, and both on_no_match outcomes.
 */
class TopologyBuilderTest {

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> in;

    @AfterEach
    void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void matchedMessagesAreRoutedToTheirRulesTopic() {
        setUp("dlq", "syslog-unmatched");

        in.pipeInput(null, "<187>RP/0/RP0/CPU0:Aug 26 10:14:22.531 UTC: ifmgr[402]: "
                + "%PKT_INFRA-LINK-3-UPDOWN : Interface HundredGigE0/0/0/0, changed state to Down");

        TestOutputTopic<String, byte[]> out = bytesTopic("net-interface-events");
        assertThat(out.isEmpty()).isFalse();
        String json = new String(out.readValue(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"interface\":\"HundredGigE0/0/0/0\"");
        assertThat(json).contains("\"state\":\"down\"");
    }

    @Test
    void unmatchedMessagesGoToTheDlqTopicWhenConfigured() {
        setUp("dlq", "syslog-unmatched");

        in.pipeInput(null, "garbage, not a syslog line at all");

        TestOutputTopic<String, byte[]> dlq = bytesTopic("syslog-unmatched");
        assertThat(dlq.isEmpty()).isFalse();
        String json = new String(dlq.readValue(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"reason\":\"no rule matched\"");
    }

    @Test
    void unmatchedMessagesProduceNothingWhenConfiguredToDrop() {
        setUp("drop", null);

        in.pipeInput(null, "garbage, not a syslog line at all");

        // Nothing to read anywhere; the point of this test is that piping
        // the input does not throw and the DLQ branch stays unwired.
        assertThat(bytesTopic("net-interface-events").isEmpty()).isTrue();
    }

    private TestOutputTopic<String, byte[]> bytesTopic(String name) {
        return driver.createOutputTopic(name, Serdes.String().deserializer(), Serdes.ByteArray().deserializer());
    }

    private void setUp(String onNoMatchAction, String onNoMatchTopic) {
        ServiceConfig service = new ServiceConfig(
                new ServiceConfig.KafkaConfig("localhost:9092", "test-app",
                        new ServiceConfig.InputConfig("syslog-raw"),
                        new ServiceConfig.StreamsConfig(1, "at_least_once", 1000)),
                new ServiceConfig.RoutingConfig("first_match",
                        new ServiceConfig.OnNoMatch(onNoMatchAction, onNoMatchTopic)),
                new ServiceConfig.FieldsConfig(true, "UTC"),
                new ServiceConfig.MetricsConfig(9090),
                new ServiceConfig.HealthConfig(8080, 300));

        RulesConfig rulesConfig = ConfigLoader.loadRules(Path.of("config/rules.yaml"));
        ConfigValidator validator = new ConfigValidator(new Filters());
        ConfigValidator.Result result = validator.validateRules(rulesConfig);
        RuleEngine engine = new RuleEngine(result.rules(), RuleEngine.Strategy.FIRST_MATCH, Clock.systemUTC());

        Topology topology = TopologyBuilder.build(service, engine);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-builder-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        driver = new TopologyTestDriver(topology, props);
        in = driver.createInputTopic("syslog-raw", Serdes.String().serializer(), Serdes.String().serializer());
    }
}
