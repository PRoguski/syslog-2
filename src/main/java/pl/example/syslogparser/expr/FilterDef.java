package pl.example.syslogparser.expr;

/**
 * Registered filter: its name, the type it accepts and returns, how many
 * arguments it takes, and the implementation itself. {@link ExpressionCompiler}
 * uses {@code in}/{@code out}/{@code arity} to type-check a pipeline at
 * startup, before any message is processed.
 */
public record FilterDef(String name, Class<?> in, Class<?> out, int arity, Filter fn) {
}
