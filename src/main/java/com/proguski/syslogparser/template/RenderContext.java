package com.proguski.syslogparser.template;

import java.time.Clock;
import java.util.Map;

/**
 * Variables visible inside {{ }}: named regex groups, "raw" and flattened Kafka
 * metadata ("kafka.key", "kafka.partition", ...). The clock backs now() and the
 * year completion in parse_ts, and is frozen by golden tests.
 */
public record RenderContext(Map<String, Object> vars, Clock clock) {

    public Object lookup(String name) {
        return vars.get(name);
    }

    public boolean has(String name) {
        return vars.containsKey(name);
    }
}
