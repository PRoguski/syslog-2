package pl.example.syslogparser.streams;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.example.syslogparser.config.ServiceConfig;

import java.time.Duration;
import java.util.Properties;

/**
 * Owns the {@link KafkaStreams} lifecycle: properties, exception handlers,
 * graceful shutdown.
 *
 * <p>{@code DEFAULT_PRODUCTION_EXCEPTION_HANDLER} is left at Kafka Streams'
 * own default ({@code DefaultProductionExceptionHandler}, which fails the
 * thread) — losing data silently on a production error is not acceptable
 * here, so there is nothing to override.
 */
public final class StreamsRunner {

    private static final Logger log = LoggerFactory.getLogger(StreamsRunner.class);

    private final KafkaStreams streams;

    public StreamsRunner(ServiceConfig cfg, Topology topology) {
        this.streams = new KafkaStreams(topology, propertiesFrom(cfg));
        streams.setUncaughtExceptionHandler(this::handleUncaught);
    }

    private static Properties propertiesFrom(ServiceConfig cfg) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, cfg.kafka().applicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.kafka().bootstrap());
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, cfg.kafka().streams().numStreamThreads());
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, cfg.kafka().streams().processingGuarantee());
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, cfg.kafka().streams().commitIntervalMs());
        // Never let a bad message kill a Streams thread: log it and keep going
        // (RuleEngine already turns render errors into DLQ records; this only
        // covers records that fail to even deserialize).
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class);
        return props;
    }

    private StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse handleUncaught(Throwable t) {
        log.error("uncaught exception in a Streams thread — replacing it", t);
        return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "streams-shutdown"));
        streams.start();
    }

    public void close() {
        log.info("shutting down Kafka Streams");
        streams.close(Duration.ofSeconds(30));
    }
}
