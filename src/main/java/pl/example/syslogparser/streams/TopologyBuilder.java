package pl.example.syslogparser.streams;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.TopicNameExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.example.syslogparser.config.ServiceConfig;
import pl.example.syslogparser.engine.MatchResult;
import pl.example.syslogparser.engine.RuleEngine;

import java.util.Map;

/**
 * Builds the topology: {@code raw line -> RuleEngine -> split on outcome}.
 * Matched records go to whatever topic their rule named, via a
 * {@link TopicNameExtractor} (one topic per record, not per stream — the
 * whole point of {@code output.topic} being per-rule). Everything else is
 * handled per {@code routing.on_no_match}.
 */
public final class TopologyBuilder {

    private static final Logger log = LoggerFactory.getLogger(TopologyBuilder.class);

    private TopologyBuilder() {
    }

    public static Topology build(ServiceConfig cfg, RuleEngine engine) {
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> raw = builder.stream(
                cfg.kafka().input().topic(), Consumed.with(Serdes.String(), Serdes.String()));
        KStream<String, MatchResult> results = raw.process(() -> new MatchProcessor(engine));

        Map<String, KStream<String, MatchResult>> branches = results.split(Named.as("r-"))
                .branch((k, r) -> r instanceof MatchResult.Matched, Branched.as("ok"))
                .branch((k, r) -> !(r instanceof MatchResult.Matched), Branched.as("err"))
                .noDefaultBranch();

        wireMatched(branches.get("r-ok"));
        wireOnNoMatch(branches.get("r-err"), cfg);

        return builder.build();
    }

    private static void wireMatched(KStream<String, MatchResult> ok) {
        TopicNameExtractor<String, MatchResult.Matched> topicOf = (key, value, ctx) -> value.topic();

        ok.mapValues(r -> (MatchResult.Matched) r)
                .map((k, m) -> KeyValue.pair(m.key().orElse(k), m))
                .to(topicOf, Produced.with(Serdes.String(), JsonSerdes.matched()));
    }

    private static void wireOnNoMatch(KStream<String, MatchResult> err, ServiceConfig cfg) {
        String action = cfg.routing().onNoMatch().action();
        switch (action) {
            case "drop" -> err.foreach((k, r) -> log.debug("dropped per on_no_match.action=drop: {}", r));
            case "dlq", "passthrough" -> err.mapValues(DlqPayload::of)
                    .to(cfg.routing().onNoMatch().topic(), Produced.with(Serdes.String(), JsonSerdes.dlq()));
            default -> throw new IllegalStateException("unknown routing.on_no_match.action: " + action);
        }
    }
}
