package pl.example.syslogparser.expr;

import java.util.List;

/** A single, stateless, thread-safe transformation step in a pipeline. */
@FunctionalInterface
public interface Filter {
    Object apply(Object input, List<String> args);
}
