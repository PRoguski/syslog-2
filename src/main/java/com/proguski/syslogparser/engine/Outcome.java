package com.proguski.syslogparser.engine;

import java.util.List;
import java.util.Map;

/** Result of pushing one raw line through the rule engine. */
public sealed interface Outcome {

    /** One output message: rule that produced it, target topic, partition key and JSON fields. */
    record Emission(String rule, String topic, String key, Map<String, Object> fields) {}

    /** At least one rule matched and rendered. */
    record Matched(List<Emission> emissions) implements Outcome {}

    /** No rule matched (or every candidate failed template rendering). */
    record Unmatched(String reason) implements Outcome {}
}
