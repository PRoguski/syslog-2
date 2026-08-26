package pl.example.syslogparser.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.example.syslogparser.Bootstrap;
import pl.example.syslogparser.config.ConfigError;
import pl.example.syslogparser.engine.MatchResult;
import pl.example.syslogparser.expr.KafkaMeta;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "dry-run",
        description = "Match one raw syslog line against rules.yaml and print rule, topic, key and JSON — no Kafka involved.")
public final class DryRunCommand implements Callable<Integer> {

    private static final ObjectMapper PRETTY = new ObjectMapper();

    @Option(names = "--rules", required = true, description = "Path to rules.yaml")
    Path rulesPath;

    @Parameters(index = "0", description = "The raw syslog line to test, quoted.")
    String line;

    @Override
    public Integer call() throws com.fasterxml.jackson.core.JsonProcessingException {
        try {
            Bootstrap.Loaded loaded = Bootstrap.loadRulesOnly(rulesPath);
            List<MatchResult> results = loaded.engine().match(line, new KafkaMeta(-1, -1L, Instant.now()));
            for (MatchResult r : results) {
                print(r);
            }
            return results.stream().anyMatch(r -> r instanceof MatchResult.Matched) ? 0 : 1;
        } catch (ConfigError e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private void print(MatchResult r) throws com.fasterxml.jackson.core.JsonProcessingException {
        switch (r) {
            case MatchResult.Matched m -> {
                System.out.println("rule:  " + m.ruleName());
                System.out.println("topic: " + m.topic());
                System.out.println("key:   " + m.key().orElse("<none>"));
                System.out.println(PRETTY.writerWithDefaultPrettyPrinter().writeValueAsString(m.json()));
            }
            case MatchResult.Unmatched u -> System.out.println("no rule matched");
            case MatchResult.RenderError e -> System.out.println(
                    "rule '" + e.ruleName() + "' matched but failed to render: " + e.cause());
        }
    }
}
