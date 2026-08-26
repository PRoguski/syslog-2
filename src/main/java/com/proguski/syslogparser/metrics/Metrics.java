package com.proguski.syslogparser.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;

import java.io.IOException;

/** Prometheus metrics per the observability section of the design plan. */
public final class Metrics {

    public final Counter consumedTotal = Counter.build()
            .name("consumed_total").help("Messages consumed from the input topic").register();
    public final Counter producedTotal = Counter.build()
            .name("produced_total").help("Messages produced").labelNames("rule", "topic").register();
    public final Counter matchedTotal = Counter.build()
            .name("matched_total").help("Messages matched per rule").labelNames("rule").register();
    public final Counter unmatchedTotal = Counter.build()
            .name("unmatched_total").help("Messages matching no rule").register();
    public final Counter dlqTotal = Counter.build()
            .name("dlq_total").help("Messages sent to the DLQ").register();
    public final Counter templateErrorsTotal = Counter.build()
            .name("template_errors_total").help("Template render errors").labelNames("rule").register();
    public final Histogram processingDuration = Histogram.build()
            .name("processing_duration_seconds").help("Per-message processing time").register();
    public final Gauge lastProcessedTimestamp = Gauge.build()
            .name("last_processed_timestamp_seconds")
            .help("Unix time of the last processed message (healthcheck)").register();

    private HTTPServer server;

    public void startHttpServer(int port) throws IOException {
        server = new HTTPServer(port);
    }

    public void stop() {
        if (server != null) {
            server.close();
        }
    }
}
