package pl.example.syslogparser.expr;

import java.util.List;

/**
 * A field's value: either a literal (whatever was written in YAML, verbatim),
 * or a {@code {{ source | filter | filter... }}} pipeline.
 *
 * <p>The type returned by {@link #eval} is whatever the last filter in the
 * pipeline produces ({@code Long}, {@code Boolean}, {@code Instant},
 * {@code String}, or {@code null}) — {@link pl.example.syslogparser.engine.RuleEngine}
 * maps that straight onto the matching {@code JsonNode} type, no string
 * concatenation involved.
 */
public sealed interface Value {

    Object eval(Context ctx);

    record Literal(Object constant) implements Value {
        @Override
        public Object eval(Context ctx) {
            return constant;
        }
    }

    record Pipeline(Source source, List<Step> steps) implements Value {

        public record Step(FilterDef def, List<Arg> args) {
        }

        @Override
        public Object eval(Context ctx) {
            Object v = ctx.resolve(source);
            for (Step s : steps) {
                List<String> resolvedArgs = s.args().stream().map(a -> a.resolve(ctx)).toList();
                v = s.def().fn().apply(v, resolvedArgs);
            }
            return v;
        }
    }

    /** One argument passed to a filter: a quoted literal, or a bare reference to a source. */
    sealed interface Arg {

        String resolve(Context ctx);

        record Literal(String value) implements Arg {
            @Override
            public String resolve(Context ctx) {
                return value;
            }
        }

        record Ref(Source source) implements Arg {
            @Override
            public String resolve(Context ctx) {
                Object v = ctx.resolve(source);
                return v == null ? null : String.valueOf(v);
            }
        }
    }
}
