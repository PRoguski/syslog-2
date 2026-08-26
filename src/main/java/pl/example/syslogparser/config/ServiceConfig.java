package pl.example.syslogparser.config;

/**
 * Root of {@code service.yaml} — Kafka connectivity, routing policy and
 * operational knobs. Changes rarely; deployed as part of the pod/container config.
 */
public record ServiceConfig(
        KafkaConfig kafka,
        RoutingConfig routing,
        FieldsConfig fields,
        MetricsConfig metrics,
        HealthConfig health) {

    public record KafkaConfig(
            String bootstrap,
            String applicationId,
            InputConfig input,
            StreamsConfig streams) {
    }

    public record InputConfig(String topic) {
    }

    public record StreamsConfig(
            int numStreamThreads,
            String processingGuarantee,
            long commitIntervalMs) {

        public StreamsConfig {
            if (numStreamThreads <= 0) {
                numStreamThreads = 1;
            }
            if (processingGuarantee == null) {
                processingGuarantee = "at_least_once";
            }
            if (commitIntervalMs <= 0) {
                commitIntervalMs = 1000;
            }
        }
    }

    public record RoutingConfig(String strategy, OnNoMatch onNoMatch) {
        public RoutingConfig {
            if (strategy == null) {
                strategy = "first_match";
            }
        }
    }

    public record OnNoMatch(String action, String topic) {
        public OnNoMatch {
            if (action == null) {
                action = "drop";
            }
        }
    }

    public record FieldsConfig(boolean assumeCurrentYear, String defaultTimezone) {
        public FieldsConfig {
            if (defaultTimezone == null) {
                defaultTimezone = "UTC";
            }
        }
    }

    public record MetricsConfig(int port) {
    }

    public record HealthConfig(int port, int staleAfterSeconds) {
    }
}
