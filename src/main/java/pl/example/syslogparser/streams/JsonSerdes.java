package pl.example.syslogparser.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import pl.example.syslogparser.engine.MatchResult;

/**
 * Output-only JSON serdes for the topology's producer side. There is
 * deliberately no working deserializer: this service never reads these
 * topics back, so a real one would be dead code the type-checker can't catch.
 */
final class JsonSerdes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSerdes() {
    }

    static Serde<MatchResult.Matched> matched() {
        Serializer<MatchResult.Matched> serializer = (topic, m) -> {
            try {
                return MAPPER.writeValueAsBytes(m.json());
            } catch (Exception e) {
                throw new SerializationException("failed to serialize matched record for topic " + topic, e);
            }
        };
        return Serdes.serdeFrom(serializer, neverDeserialize("matched"));
    }

    static Serde<DlqPayload> dlq() {
        Serializer<DlqPayload> serializer = (topic, p) -> {
            try {
                return MAPPER.writeValueAsBytes(p);
            } catch (Exception e) {
                throw new SerializationException("failed to serialize DLQ payload for topic " + topic, e);
            }
        };
        return Serdes.serdeFrom(serializer, neverDeserialize("DLQ"));
    }

    private static <T> Deserializer<T> neverDeserialize(String what) {
        return (topic, bytes) -> {
            throw new UnsupportedOperationException(what + " records are produced only, never consumed back");
        };
    }
}
