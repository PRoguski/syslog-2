package pl.example.syslogparser.cli;

import org.apache.kafka.streams.Topology;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.example.syslogparser.Bootstrap;
import pl.example.syslogparser.config.ConfigError;
import pl.example.syslogparser.streams.StreamsRunner;
import pl.example.syslogparser.streams.TopologyBuilder;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "run", description = "Start the service: consume from Kafka, match rules, produce JSON.")
public final class RunCommand implements Callable<Integer> {

    @Option(names = "--service", required = true, description = "Path to service.yaml")
    Path servicePath;

    @Option(names = "--rules", required = true, description = "Path to rules.yaml")
    Path rulesPath;

    @Override
    public Integer call() throws InterruptedException {
        Bootstrap.Loaded loaded;
        try {
            loaded = Bootstrap.load(servicePath, rulesPath);
        } catch (ConfigError e) {
            System.err.println(e.getMessage());
            return 1;
        }

        Topology topology = TopologyBuilder.build(loaded.service(), loaded.engine());
        StreamsRunner runner = new StreamsRunner(loaded.service(), topology);
        runner.start();

        // StreamsRunner installs its own shutdown hook that closes the
        // KafkaStreams instance gracefully; just keep the main thread alive
        // until the process is asked to stop.
        new CountDownLatch(1).await();
        return 0;
    }
}
