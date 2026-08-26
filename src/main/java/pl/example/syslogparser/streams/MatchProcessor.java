package pl.example.syslogparser.streams;

import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import pl.example.syslogparser.engine.MatchResult;
import pl.example.syslogparser.engine.RuleEngine;
import pl.example.syslogparser.expr.KafkaMeta;

import java.time.Instant;

/**
 * Bridges the Streams DSL and {@link RuleEngine}. Deliberately uses the
 * Processor API rather than {@code flatMapValues}: the DSL's value mappers
 * have no access to record metadata, but the {@code kafka.partition} /
 * {@code kafka.offset} builtins in expressions need it. {@link #process}
 * forwards zero, one, or several output records per input line — zero for
 * nothing (never happens, {@code RuleEngine.match} always returns at least
 * an {@code Unmatched}), several under {@code all_matches}.
 */
final class MatchProcessor implements Processor<String, String, String, MatchResult> {

    private final RuleEngine engine;
    private ProcessorContext<String, MatchResult> context;

    MatchProcessor(RuleEngine engine) {
        this.engine = engine;
    }

    @Override
    public void init(ProcessorContext<String, MatchResult> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, String> record) {
        Instant recordTime = Instant.ofEpochMilli(record.timestamp());
        KafkaMeta meta = context.recordMetadata()
                .map(m -> new KafkaMeta(m.partition(), m.offset(), recordTime))
                .orElseGet(() -> KafkaMeta.unknown(recordTime));

        for (MatchResult result : engine.match(record.value(), meta)) {
            context.forward(record.withValue(result));
        }
    }
}
