package pl.example.syslogparser.expr;

import java.time.Instant;

/**
 * Kafka record metadata made available to expressions as {@code kafka.partition},
 * {@code kafka.offset}, {@code kafka.timestamp}.
 */
public record KafkaMeta(int partition, long offset, Instant timestamp) {

    public static KafkaMeta unknown(Instant timestamp) {
        return new KafkaMeta(-1, -1L, timestamp);
    }
}
