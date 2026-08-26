package com.proguski.syslogparser.engine;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.proguski.syslogparser.config.ConfigException;
import com.proguski.syslogparser.config.RulesConfig;
import com.proguski.syslogparser.config.ServiceConfig;
import com.proguski.syslogparser.template.Expr;
import com.proguski.syslogparser.template.Filters;
import com.proguski.syslogparser.template.RenderContext;
import com.proguski.syslogparser.template.TemplateException;
import com.proguski.syslogparser.template.ValueTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Compiles rules.yaml (defines substitution, RE2 regexes, templates), validates
 * everything fail-fast at start-up, and matches raw syslog lines:
 * prefilter -> regex -> context -> render.
 */
public final class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<CompiledRule> rules;
    private final ServiceConfig.RoutingStrategy strategy;
    private final Clock clock;
    /** Called on a render failure with (rule name, message); pluggable for metrics. */
    private BiConsumer<String, String> templateErrorListener = (rule, msg) ->
            log.warn("template error in rule \"{}\": {}", rule, msg);

    private RuleEngine(List<CompiledRule> rules, ServiceConfig.RoutingStrategy strategy, Clock clock) {
        this.rules = rules;
        this.strategy = strategy;
        this.clock = clock;
    }

    public void onTemplateError(BiConsumer<String, String> listener) {
        this.templateErrorListener = listener;
    }

    public List<CompiledRule> rules() {
        return rules;
    }

    // ------------------------------------------------------------ compilation

    public static RuleEngine build(ServiceConfig service, RulesConfig rulesConfig, Clock clock) {
        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (RulesConfig.RuleConfig rc : rulesConfig.rules()) {
            String where = "rule \"" + rc.name() + "\"";
            if (!names.add(rc.name())) {
                throw new ConfigException("duplicate rule name: \"" + rc.name() + "\"");
            }
            if (!rc.enabled()) {
                continue;
            }

            String regexSrc = substituteDefines(rc.regex(), rulesConfig.defines(), where);
            Pattern pattern;
            try {
                pattern = Pattern.compile(regexSrc);
            } catch (com.google.re2j.PatternSyntaxException e) {
                throw new ConfigException(where + ": regex does not compile: " + e.getMessage());
            }
            List<String> groupNames = extractGroupNames(regexSrc);

            Map<String, Object> mergedTemplate = new LinkedHashMap<>(rulesConfig.defaultsTemplate());
            mergedTemplate.putAll(rc.template());

            Map<String, ValueTemplate> template = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : mergedTemplate.entrySet()) {
                try {
                    template.put(e.getKey(), ValueTemplate.compile(e.getValue()));
                } catch (TemplateException ex) {
                    throw new ConfigException(
                            where + ": template field \"" + e.getKey() + "\": " + ex.getMessage());
                }
            }
            ValueTemplate keyTemplate = null;
            if (rc.outputKey() != null) {
                try {
                    keyTemplate = ValueTemplate.compile(rc.outputKey());
                } catch (TemplateException ex) {
                    throw new ConfigException(where + ": output.key: " + ex.getMessage());
                }
            }

            CompiledRule rule = new CompiledRule(rc.name(), rc.prefilter(), pattern,
                    groupNames, rc.outputTopic(), keyTemplate, template);
            validateRule(rule);
            compiled.add(rule);
        }

        if (compiled.isEmpty()) {
            throw new ConfigException("no enabled rules configured");
        }
        return new RuleEngine(compiled, service.strategy(), clock);
    }

    /** Replaces {{name}} placeholders (no spaces) with fragments from `defines`, before compilation. */
    static String substituteDefines(String regex, Map<String, String> defines, String where) {
        String out = regex;
        for (Map.Entry<String, String> d : defines.entrySet()) {
            out = out.replace("{{" + d.getKey() + "}}", d.getValue());
        }
        int open = out.indexOf("{{");
        if (open >= 0) {
            int close = out.indexOf("}}", open);
            String ref = close > 0 ? out.substring(open + 2, close) : "?";
            throw new ConfigException(where + ": regex references unknown define \"" + ref + "\"");
        }
        return out;
    }

    /** Named groups in source order, scanned from the pattern text ((?P<name>) or (?<name>)). */
    static List<String> extractGroupNames(String regex) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < regex.length() - 3; i++) {
            if (regex.charAt(i) != '(' || regex.charAt(i + 1) != '?') continue;
            int nameStart;
            if (regex.charAt(i + 2) == 'P' && regex.charAt(i + 3) == '<') {
                nameStart = i + 4;
            } else if (regex.charAt(i + 2) == '<' && regex.charAt(i + 3) != '=' && regex.charAt(i + 3) != '!') {
                nameStart = i + 3;
            } else {
                continue;
            }
            int end = regex.indexOf('>', nameStart);
            if (end > nameStart) {
                names.add(regex.substring(nameStart, end));
            }
        }
        return names;
    }

    /**
     * Start-up validation (fail-fast): every variable used by the template and
     * output.key must exist in the regex; filters/functions must exist with the
     * right argument counts. Unused groups only warn.
     */
    private static void validateRule(CompiledRule rule) {
        String where = "rule \"" + rule.name() + "\"";
        Set<String> used = new LinkedHashSet<>();
        List<Expr.Usage> usages = new ArrayList<>();
        for (ValueTemplate vt : rule.template().values()) {
            vt.collect(used, usages);
        }
        if (rule.outputKey() != null) {
            rule.outputKey().collect(used, usages);
        }

        Set<String> available = new HashSet<>(rule.groupNames());
        available.add("raw");
        for (String var : used) {
            if (!available.contains(var) && !var.startsWith("kafka.")) {
                throw new ConfigException(
                        where + ": template uses group \"" + var + "\" not present in regex");
            }
        }
        for (Expr.Usage u : usages) {
            Map<String, int[]> registry = u.isFilter() ? Filters.FILTER_ARITY : Filters.FUNCTION_ARITY;
            int[] arity = registry.get(u.name());
            String kind = u.isFilter() ? "filter" : "function";
            if (arity == null) {
                throw new ConfigException(where + ": unknown " + kind + " \"" + u.name() + "\"");
            }
            if (u.argCount() < arity[0] || u.argCount() > arity[1]) {
                throw new ConfigException(where + ": " + kind + " \"" + u.name() + "\" expects "
                        + (arity[0] == arity[1] ? String.valueOf(arity[0]) : arity[0] + ".." + arity[1])
                        + " argument(s), got " + u.argCount());
            }
        }
        for (String group : rule.groupNames()) {
            if (!used.contains(group)) {
                log.warn("{}: regex group \"{}\" is never used by the template", where, group);
            }
        }
    }

    // -------------------------------------------------------------- matching

    public Outcome process(String raw) {
        return process(raw, Map.of());
    }

    /**
     * prefilter -> regex -> context -> render. A template error counts as a
     * non-match for that rule (and never kills the pipeline).
     */
    public Outcome process(String raw, Map<String, Object> kafkaMeta) {
        List<Outcome.Emission> emissions = new ArrayList<>(1);
        String lastError = null;

        for (CompiledRule rule : rules) {
            if (rule.prefilter() != null && !raw.contains(rule.prefilter())) {
                continue;
            }
            Matcher m = rule.pattern().matcher(raw);
            if (!m.find()) {
                continue;
            }
            Map<String, Object> vars = new HashMap<>();
            for (String g : rule.groupNames()) {
                vars.put(g, m.group(g));
            }
            vars.put("raw", raw);
            vars.putAll(kafkaMeta);
            RenderContext ctx = new RenderContext(vars, clock);

            try {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (Map.Entry<String, ValueTemplate> e : rule.template().entrySet()) {
                    fields.put(e.getKey(), e.getValue().render(ctx));
                }
                String key = rule.outputKey() != null ? rule.outputKey().renderString(ctx) : null;
                emissions.add(new Outcome.Emission(rule.name(), rule.outputTopic(), key, fields));
            } catch (TemplateException ex) {
                lastError = "rule \"" + rule.name() + "\": " + ex.getMessage();
                templateErrorListener.accept(rule.name(), ex.getMessage());
                continue; // treated like no-match for this rule
            }

            if (strategy == ServiceConfig.RoutingStrategy.FIRST_MATCH) {
                return new Outcome.Matched(emissions);
            }
        }
        if (emissions.isEmpty()) {
            return new Outcome.Unmatched(lastError != null ? lastError : "no rule matched");
        }
        return new Outcome.Matched(emissions);
    }
}
