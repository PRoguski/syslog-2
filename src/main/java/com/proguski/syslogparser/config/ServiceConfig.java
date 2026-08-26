package com.proguski.syslogparser.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** service.yaml — Kafka, routing and metrics settings (rarely changed). */
public record ServiceConfig(
        String kafkaBootstrap,
        String inputTopic,
        String groupId,
        boolean producerIdempotent,
        RoutingStrategy strategy,
        NoMatchAction onNoMatchAction,
        String onNoMatchTopic,
        int metricsPort) {

    public enum RoutingStrategy { FIRST_MATCH, ALL_MATCHES }

    public enum NoMatchAction { DLQ, DROP, PASSTHROUGH }

    public static ServiceConfig load(Path path) {
        Map<String, Object> root = Yaml.loadFile(path);

        Map<String, Object> kafka = Yaml.map(root, "kafka", "service");
        Map<String, Object> input = Yaml.map(kafka, "input", "service.kafka");
        Map<String, Object> producer = Yaml.map(kafka, "producer", "service.kafka");

        Map<String, Object> routing = Yaml.map(root, "routing", "service");
        Map<String, Object> onNoMatch = Yaml.map(routing, "on_no_match", "service.routing");
        Map<String, Object> metrics = Yaml.map(root, "metrics", "service");

        RoutingStrategy strategy = switch (Yaml.str(routing, "strategy", "first_match")
                .toLowerCase(Locale.ROOT)) {
            case "first_match" -> RoutingStrategy.FIRST_MATCH;
            case "all_matches" -> RoutingStrategy.ALL_MATCHES;
            default -> throw new ConfigException(
                    "routing.strategy must be first_match or all_matches, got: "
                            + routing.get("strategy"));
        };

        NoMatchAction action = switch (Yaml.str(onNoMatch, "action", "dlq")
                .toLowerCase(Locale.ROOT)) {
            case "dlq" -> NoMatchAction.DLQ;
            case "drop" -> NoMatchAction.DROP;
            case "passthrough" -> NoMatchAction.PASSTHROUGH;
            default -> throw new ConfigException(
                    "routing.on_no_match.action must be dlq, drop or passthrough, got: "
                            + onNoMatch.get("action"));
        };

        String noMatchTopic = Yaml.str(onNoMatch, "topic", null);
        if (action != NoMatchAction.DROP && (noMatchTopic == null || noMatchTopic.isBlank())) {
            throw new ConfigException(
                    "routing.on_no_match.topic is required for action " + action.name().toLowerCase(Locale.ROOT));
        }

        return new ServiceConfig(
                Yaml.str(kafka, "bootstrap", "localhost:9092"),
                Yaml.str(input, "topic", "syslog-raw"),
                Yaml.str(input, "group_id", "syslog-parser"),
                Yaml.boolVal(producer, "idempotent", true),
                strategy,
                action,
                noMatchTopic,
                Yaml.intVal(metrics, "port", 9090));
    }
}
