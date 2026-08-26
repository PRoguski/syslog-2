package pl.example.syslogparser.expr;

/**
 * Where a pipeline's first value comes from, before any filter runs.
 *
 * <ul>
 *   <li>{@link Group} — a named capturing group from the rule's regex.</li>
 *   <li>{@link Builtin} — a value the engine always provides ({@code raw},
 *       {@code kafka.partition}, {@code kafka.offset}, {@code kafka.timestamp}).</li>
 *   <li>{@link Call} — a zero-argument producer function, e.g. {@code now()}.</li>
 * </ul>
 */
public sealed interface Source {

    String name();

    record Group(String name) implements Source {
    }

    record Builtin(String name) implements Source {
    }

    record Call(String name) implements Source {
    }
}
