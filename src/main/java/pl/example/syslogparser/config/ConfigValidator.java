package pl.example.syslogparser.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.example.syslogparser.engine.CompiledRule;
import pl.example.syslogparser.expr.ExpressionCompiler;
import pl.example.syslogparser.expr.Filters;
import pl.example.syslogparser.expr.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Fail-fast validation of {@code service.yaml} / {@code rules.yaml}: every
 * problem is collected and reported together (see {@link ConfigError}),
 * rather than stopping at the first one.
 */
public final class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);
    private static final Set<String> STRATEGIES = Set.of("first_match", "all_matches");
    private static final Set<String> NO_MATCH_ACTIONS = Set.of("dlq", "drop", "passthrough");

    private final Filters filters;

    public ConfigValidator(Filters filters) {
        this.filters = filters;
    }

    public record Result(List<CompiledRule> rules) {
    }

    public void validateService(ServiceConfig cfg) {
        List<ConfigError.Issue> issues = new ArrayList<>();

        if (blank(cfg.kafka() == null ? null : cfg.kafka().bootstrap())) {
            issues.add(new ConfigError.Issue(null, "kafka.bootstrap", "must not be empty"));
        }
        if (cfg.kafka() == null || cfg.kafka().input() == null || blank(cfg.kafka().input().topic())) {
            issues.add(new ConfigError.Issue(null, "kafka.input.topic", "must not be empty"));
        }
        if (!STRATEGIES.contains(cfg.routing().strategy())) {
            issues.add(new ConfigError.Issue(null, "routing.strategy", "must be one of " + STRATEGIES));
        }

        ServiceConfig.OnNoMatch onNoMatch = cfg.routing().onNoMatch();
        String action = onNoMatch == null ? null : onNoMatch.action();
        if (!NO_MATCH_ACTIONS.contains(action)) {
            issues.add(new ConfigError.Issue(null, "routing.on_no_match.action", "must be one of " + NO_MATCH_ACTIONS));
        } else if ("dlq".equals(action) && blank(onNoMatch.topic())) {
            issues.add(new ConfigError.Issue(null, "routing.on_no_match.topic", "required when action is 'dlq'"));
        }

        if (!issues.isEmpty()) {
            throw new ConfigError(issues);
        }
    }

    public Result validateRules(RulesConfig config) {
        List<ConfigError.Issue> issues = new ArrayList<>();
        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        List<RulesConfig.RuleDef> rules = config.rules();
        for (int i = 0; i < rules.size(); i++) {
            validateOne(config, rules.get(i), i, seenNames, issues, compiled);
        }

        if (!issues.isEmpty()) {
            throw new ConfigError(issues);
        }
        return new Result(compiled);
    }

    private void validateOne(
            RulesConfig config,
            RulesConfig.RuleDef rule,
            int index,
            Set<String> seenNames,
            List<ConfigError.Issue> issues,
            List<CompiledRule> compiled) {

        List<ConfigError.Issue> ruleIssues = new ArrayList<>();
        String name = blank(rule.name()) ? "<rule #%d>".formatted(index + 1) : rule.name();

        if (blank(rule.name())) {
            issues.add(new ConfigError.Issue(name, null, "missing 'name'"));
            return;
        }
        if (!seenNames.add(name)) {
            issues.add(new ConfigError.Issue(name, null, "duplicate rule name"));
            return;
        }
        if (!rule.isEnabled()) {
            return;
        }
        if (rule.output() == null || blank(rule.output().topic())) {
            ruleIssues.add(new ConfigError.Issue(name, "output.topic", "must not be empty"));
        }
        if (blank(rule.regex())) {
            issues.add(new ConfigError.Issue(name, "regex", "must not be empty"));
            return;
        }

        String substituted = substituteDefines(rule.regex(), config.defines());
        Pattern pattern;
        try {
            pattern = Pattern.compile(substituted);
        } catch (PatternSyntaxException e) {
            issues.add(new ConfigError.Issue(name, "regex", "invalid pattern: " + e.getMessage()));
            return;
        }

        if (!isAnchored(substituted)) {
            log.warn("rule \"{}\": regex is not anchored with ^...$ — unanchored rules can partially match "
                    + "unrelated lines and are sensitive to rule order", name);
        }

        Set<String> groupNames = pattern.namedGroups().keySet();

        Map<String, String> fieldExprs = new LinkedHashMap<>(config.defaults().fields());
        fieldExprs.putAll(rule.fields());

        Map<String, Value> compiledFields = new LinkedHashMap<>();
        for (var entry : fieldExprs.entrySet()) {
            try {
                compiledFields.put(entry.getKey(),
                        ExpressionCompiler.compile(entry.getValue(), groupNames, filters, name, entry.getKey()));
            } catch (ConfigError e) {
                ruleIssues.addAll(e.issues());
            }
        }

        warnUnusedGroups(name, groupNames, fieldExprs.values());

        Value keyValue = null;
        if (rule.output() != null && !blank(rule.output().key())) {
            try {
                keyValue = ExpressionCompiler.compile(rule.output().key(), groupNames, filters, name, "output.key");
            } catch (ConfigError e) {
                ruleIssues.addAll(e.issues());
            }
        }

        if (ruleIssues.isEmpty()) {
            String topic = rule.output() == null ? null : rule.output().topic();
            compiled.add(new CompiledRule(name, pattern, rule.prefilter(), topic, keyValue, compiledFields));
        } else {
            issues.addAll(ruleIssues);
        }
    }

    private static void warnUnusedGroups(String ruleName, Set<String> groupNames, Iterable<String> exprs) {
        Set<String> used = new HashSet<>();
        for (String expr : exprs) {
            for (String g : groupNames) {
                if (expr.contains(g)) {
                    used.add(g);
                }
            }
        }
        for (String g : groupNames) {
            if (!used.contains(g)) {
                log.warn("rule \"{}\": group '{}' is captured but not used by any field", ruleName, g);
            }
        }
    }

    private static boolean isAnchored(String regex) {
        return regex.startsWith("^") && (regex.endsWith("$") || regex.endsWith("$)"));
    }

    private static String substituteDefines(String regex, Map<String, String> defines) {
        String result = regex;
        for (var e : defines.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return result;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
