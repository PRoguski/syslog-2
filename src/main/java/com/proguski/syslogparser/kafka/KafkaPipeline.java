package com.proguski.syslogparser.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proguski.syslogparser.config.ServiceConfig;
import com.proguski.syslogparser.engine.Outcome;
import com.proguski.syslogparser.engine.RuleEngine;
import com.proguski.syslogparser.metrics.Metrics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * consume -> decode UTF-8 -> rule engine -> JSON -> produce -> commit after ack.
 * At-least-once: offsets are committed only after every produce of the batch is
 * acknowledged; on failure the batch is rewound and retried with backoff.
 */
public final class KafkaPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaPipeline.class);

    private final ServiceConfig config;
    private final RuleEngine engine;
    private final Metrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final KafkaConsumer<byte[], byte[]> consumer;
    private final KafkaProducer<byte[], byte[]> producer;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaPipeline(ServiceConfig config, RuleEngine engine, Metrics metrics) {
        this.config = config;
        this.engine = engine;
        this.metrics = metrics;
        engine.onTemplateError((rule, msg) -> {
            metrics.templateErrorsTotal.labels(rule).inc();
            log.warn("template error in rule \"{}\": {}", rule, msg);
        });

        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap());
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId());
        cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(cp);

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap());
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, String.valueOf(config.producerIdempotent()));
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        this.producer = new KafkaProducer<>(pp);
    }

    public void run() {
        consumer.subscribe(List.of(config.inputTopic()));
        log.info("consuming from {} (group {}), strategy {}",
                config.inputTopic(), config.groupId(), config.strategy());
        long backoffMs = 1000;
        try {
            while (running.get()) {
                ConsumerRecords<byte[], byte[]> records;
                try {
                    records = consumer.poll(Duration.ofMillis(500));
                } catch (WakeupException e) {
                    break;
                }
                if (records.isEmpty()) {
                    continue;
                }
                try {
                    List<Future<RecordMetadata>> pending = new ArrayList<>();
                    for (ConsumerRecord<byte[], byte[]> record : records) {
                        pending.addAll(processRecord(record));
                    }
                    producer.flush();
                    for (Future<RecordMetadata> f : pending) {
                        f.get(); // surfaces produce failures before commit
                    }
                    consumer.commitSync();
                    backoffMs = 1000;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("batch failed, rewinding and retrying in {} ms: {}",
                            backoffMs, e.toString());
                    rewind(records);
                    sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                }
            }
        } finally {
            log.info("shutting down: flushing producer, committing nothing further");
            try {
                producer.flush();
            } finally {
                producer.close(Duration.ofSeconds(10));
                consumer.close(Duration.ofSeconds(10));
            }
        }
    }

    private List<Future<RecordMetadata>> processRecord(ConsumerRecord<byte[], byte[]> record)
            throws Exception {
        metrics.consumedTotal.inc();
        long startNanos = System.nanoTime();

        String raw = record.value() == null
                ? "" : new String(record.value(), StandardCharsets.UTF_8);
        Map<String, Object> kafkaMeta = new HashMap<>();
        kafkaMeta.put("kafka.key", record.key() == null
                ? null : new String(record.key(), StandardCharsets.UTF_8));
        kafkaMeta.put("kafka.partition", (long) record.partition());
        kafkaMeta.put("kafka.offset", record.offset());
        kafkaMeta.put("kafka.timestamp", record.timestamp());

        Outcome outcome = engine.process(raw, kafkaMeta);
        List<Future<RecordMetadata>> futures = new ArrayList<>();

        if (outcome instanceof Outcome.Matched matched) {
            for (Outcome.Emission em : matched.emissions()) {
                metrics.matchedTotal.labels(em.rule()).inc();
                byte[] key = em.key() != null
                        ? em.key().getBytes(StandardCharsets.UTF_8)
                        : record.key(); // pass input key through unless the rule sets one
                ProducerRecord<byte[], byte[]> out = new ProducerRecord<>(
                        em.topic(), null, key, mapper.writeValueAsBytes(em.fields()),
                        record.headers()); // pass input headers through
                futures.add(producer.send(out));
                metrics.producedTotal.labels(em.rule(), em.topic()).inc();
            }
        } else {
            Outcome.Unmatched unmatched = (Outcome.Unmatched) outcome;
            metrics.unmatchedTotal.inc();
            switch (config.onNoMatchAction()) {
                case DLQ -> {
                    Map<String, Object> dlq = new LinkedHashMap<>();
                    dlq.put("raw", raw);
                    dlq.put("reason", unmatched.reason());
                    dlq.put("partition", record.partition());
                    dlq.put("offset", record.offset());
                    futures.add(producer.send(new ProducerRecord<>(
                            config.onNoMatchTopic(), null, record.key(),
                            mapper.writeValueAsBytes(dlq), record.headers())));
                    metrics.dlqTotal.inc();
                }
                case PASSTHROUGH -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("raw", raw);
                    body.put("parse_error", true);
                    futures.add(producer.send(new ProducerRecord<>(
                            config.onNoMatchTopic(), null, record.key(),
                            mapper.writeValueAsBytes(body), record.headers())));
                }
                case DROP -> { /* counted, dropped */ }
            }
        }

        metrics.processingDuration.observe((System.nanoTime() - startNanos) / 1e9);
        metrics.lastProcessedTimestamp.setToCurrentTime();
        return futures;
    }

    private void rewind(ConsumerRecords<byte[], byte[]> records) {
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<byte[], byte[]>> partRecords = records.records(tp);
            consumer.seek(tp, partRecords.get(0).offset());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Graceful stop: finish the current batch, flush, commit, close. */
    public void shutdown() {
        running.set(false);
        consumer.wakeup();
    }

    @Override
    public void close() {
        shutdown();
    }
}
