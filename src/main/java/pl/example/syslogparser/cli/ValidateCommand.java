package pl.example.syslogparser.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.example.syslogparser.Bootstrap;
import pl.example.syslogparser.config.ConfigError;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "validate", description = "Load and fully validate service.yaml + rules.yaml, without connecting to Kafka.")
public final class ValidateCommand implements Callable<Integer> {

    @Option(names = "--service", required = true, description = "Path to service.yaml")
    Path servicePath;

    @Option(names = "--rules", required = true, description = "Path to rules.yaml")
    Path rulesPath;

    @Override
    public Integer call() {
        try {
            Bootstrap.Loaded loaded = Bootstrap.load(servicePath, rulesPath);
            System.out.printf("OK: %d rule(s) compiled from %s%n", loaded.rules().rules().size(), rulesPath);
            return 0;
        } catch (ConfigError e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
