package pl.example.syslogparser.expr;

import pl.example.syslogparser.config.ConfigError;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and type-checks a field expression at startup, so a typo such as
 * {@code {{ pid | lower }}} (wrong input type) or {@code {{ pri | pri_facilty }}}
 * (unknown filter) fails config validation instead of the running service.
 *
 * <p>Grammar: {@code {{ source (| filter | filter(arg, ...))* }}}. Anything
 * not wrapped in {@code {{ }}} is a literal, taken verbatim. {@code source}
 * is either a named regex group, a builtin ({@code raw}, {@code kafka.*}),
 * or a zero-argument producer call ({@code now()}).
 */
public final class ExpressionCompiler {

    /** Values always available besides regex groups, with their static type. */
    public static final Map<String, Class<?>> BUILTIN_SOURCES = Map.of(
            "raw", String.class,
            "kafka.partition", Integer.class,
            "kafka.offset", Long.class,
            "kafka.timestamp", Instant.class);

    /** Zero-argument producer functions, e.g. {@code now()}. */
    public static final Map<String, Class<?>> BUILTIN_CALLS = Map.of(
            "now", Instant.class);

    private static final Pattern EXPR_WRAPPER = Pattern.compile("^\\{\\{\\s*(.*?)\\s*}}$", Pattern.DOTALL);
    private static final Pattern PIPE_SPLIT = Pattern.compile("\\|");
    private static final Pattern ARG_SPLIT = Pattern.compile("\\s*,\\s*");
    private static final Pattern CALL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)$", Pattern.DOTALL);

    private ExpressionCompiler() {
    }

    public static Value compile(String raw, Set<String> groups, Filters filters, String ruleName, String fieldName) {
        if (raw == null) {
            return new Value.Literal(null);
        }
        Matcher wrapper = EXPR_WRAPPER.matcher(raw.strip());
        if (!wrapper.matches()) {
            return new Value.Literal(raw);
        }

        String[] parts = PIPE_SPLIT.split(wrapper.group(1));
        Source source = resolveSource(parts[0].trim(), groups, ruleName, fieldName);
        Class<?> type = typeOf(source);

        List<Value.Pipeline.Step> steps = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            steps.add(compileStep(parts[i].trim(), type, groups, filters, ruleName, fieldName));
            type = steps.get(steps.size() - 1).def().out();
        }
        return new Value.Pipeline(source, steps);
    }

    private static Value.Pipeline.Step compileStep(
            String token, Class<?> inputType, Set<String> groups, Filters filters, String rule, String field) {

        String filterName = token;
        List<String> rawArgs = List.of();
        int paren = token.indexOf('(');
        if (paren >= 0) {
            if (!token.endsWith(")")) {
                throw error(rule, field, "malformed filter call '%s'".formatted(token));
            }
            filterName = token.substring(0, paren).trim();
            String argsPart = token.substring(paren + 1, token.length() - 1).trim();
            rawArgs = argsPart.isEmpty() ? List.of() : List.of(ARG_SPLIT.split(argsPart));
        }

        String resolvedFilterName = filterName;
        FilterDef def = filters.lookup(filterName)
                .orElseThrow(() -> error(rule, field, "unknown filter '%s'".formatted(resolvedFilterName)));

        if (!def.in().isAssignableFrom(inputType)) {
            throw error(rule, field, "filter '%s' expects %s but receives %s".formatted(
                    def.name(), def.in().getSimpleName(), inputType.getSimpleName()));
        }
        if (rawArgs.size() != def.arity()) {
            throw error(rule, field, "filter '%s' expects %d argument(s), got %d".formatted(
                    def.name(), def.arity(), rawArgs.size()));
        }

        List<Value.Arg> args = new ArrayList<>();
        for (String a : rawArgs) {
            args.add(resolveArg(a, groups, rule, field));
        }
        return new Value.Pipeline.Step(def, args);
    }

    private static Source resolveSource(String token, Set<String> groups, String rule, String field) {
        Matcher call = CALL.matcher(token);
        if (call.matches()) {
            String name = call.group(1);
            if (!call.group(2).isBlank()) {
                throw error(rule, field, "'%s()' takes no arguments".formatted(name));
            }
            if (!BUILTIN_CALLS.containsKey(name)) {
                throw error(rule, field, "unknown function '%s()'".formatted(name));
            }
            return new Source.Call(name);
        }
        if (BUILTIN_SOURCES.containsKey(token)) {
            return new Source.Builtin(token);
        }
        if (groups.contains(token)) {
            return new Source.Group(token);
        }
        throw error(rule, field, "unknown group '%s' (available: %s)".formatted(token, String.join(", ", groups)));
    }

    private static Value.Arg resolveArg(String token, Set<String> groups, String rule, String field) {
        if (isQuoted(token)) {
            return new Value.Arg.Literal(token.substring(1, token.length() - 1));
        }
        return new Value.Arg.Ref(resolveSource(token, groups, rule, field));
    }

    private static boolean isQuoted(String token) {
        return token.length() >= 2
                && (token.charAt(0) == '\'' || token.charAt(0) == '"')
                && token.charAt(token.length() - 1) == token.charAt(0);
    }

    private static Class<?> typeOf(Source source) {
        return switch (source) {
            case Source.Group g -> String.class; // regex groups are always Strings
            case Source.Builtin b -> BUILTIN_SOURCES.get(b.name());
            case Source.Call c -> BUILTIN_CALLS.get(c.name());
        };
    }

    private static ConfigError error(String rule, String field, String message) {
        return new ConfigError(List.of(new ConfigError.Issue(rule, field, message)));
    }
}
