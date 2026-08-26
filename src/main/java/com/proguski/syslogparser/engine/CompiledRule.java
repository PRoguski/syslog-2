package com.proguski.syslogparser.engine;

import com.google.re2j.Pattern;
import com.proguski.syslogparser.template.ValueTemplate;

import java.util.List;
import java.util.Map;

/**
 * A rule after start-up compilation: regex compiled once (RE2 — no catastrophic
 * backtracking), templates parsed once. groupNames is the contract between the
 * regex and the template.
 */
public record CompiledRule(
        String name,
        String prefilter,
        Pattern pattern,
        List<String> groupNames,
        String outputTopic,
        ValueTemplate outputKey,
        Map<String, ValueTemplate> template) {
}
