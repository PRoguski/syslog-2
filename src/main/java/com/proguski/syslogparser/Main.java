package com.proguski.syslogparser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proguski.syslogparser.config.ConfigException;
import com.proguski.syslogparser.config.RulesConfig;
import com.proguski.syslogparser.config.ServiceConfig;
import com.proguski.syslogparser.engine.Outcome;
import com.proguski.syslogparser.engine.RuleEngine;
import com.proguski.syslogparser.golden.GoldenRunner;
import com.proguski.syslogparser.kafka.KafkaPipeline;
import com.proguski.syslogparser.metrics.Metrics;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI:
 *   validate --config ... --rules ...            configuration check only
 *   dry-run  --config ... --rules ... "<line>"   matched rule, topic and JSON, no Kafka
 *   test     --config ... --rules ... --tests .. golden tests (CI gate for config changes)
 *   run      --config ... --rules ...            the Kafka service
 */
public final class Main {

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (ConfigException e) {
            System.err.println("configuration error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("error: " + e);
            System.exit(3);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 2;
        }
        String command = args[0];
        Map<String, String> opts = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) {
                    System.err.println("missing value for option " + args[i]);
                    return 2;
                }
                opts.put(args[i].substring(2), args[++i]);
            } else {
                positional.add(args[i]);
            }
        }

        Path configPath = Path.of(opts.getOrDefault("config", "config/service.yaml"));
        Path rulesPath = Path.of(opts.getOrDefault("rules", "config/rules.yaml"));

        ServiceConfig service = ServiceConfig.load(configPath);
        RulesConfig rules = RulesConfig.load(rulesPath);

        switch (command) {
            case "validate": {
                RuleEngine engine = RuleEngine.build(service, rules, Clock.systemUTC());
                System.out.println("OK: " + engine.rules().size() + " rule(s) valid ("
                        + rulesPath + ")");
                return 0;
            }
            case "dry-run": {
                if (positional.isEmpty()) {
                    System.err.println("dry-run needs a syslog line argument");
                    return 2;
                }
                Clock clock = opts.containsKey("clock")
                        ? Clock.fixed(Instant.parse(opts.get("clock")), ZoneOffset.UTC)
                        : Clock.systemUTC();
                return dryRun(service, rules, clock, positional.get(0));
            }
            case "test": {
                Path testsPath = Path.of(opts.getOrDefault("tests", "tests/golden.yaml"));
                GoldenRunner.Result result = GoldenRunner.run(service, rules, testsPath);
                result.lines().forEach(System.out::println);
                return result.ok() ? 0 : 1;
            }
            case "run": {
                RuleEngine engine = RuleEngine.build(service, rules, Clock.systemUTC());
                Metrics metrics = new Metrics();
                metrics.startHttpServer(service.metricsPort());
                KafkaPipeline pipeline = new KafkaPipeline(service, engine, metrics);
                Runtime.getRuntime().addShutdownHook(new Thread(pipeline::shutdown, "shutdown"));
                try {
                    pipeline.run();
                } finally {
                    metrics.stop();
                }
                return 0;
            }
            default:
                usage();
                return 2;
        }
    }

    private static int dryRun(ServiceConfig service, RulesConfig rules, Clock clock, String line)
            throws Exception {
        RuleEngine engine = RuleEngine.build(service, rules, clock);
        ObjectMapper mapper = new ObjectMapper();
        Outcome outcome = engine.process(line);

        if (outcome instanceof Outcome.Unmatched u) {
            System.out.println("no match (" + u.reason() + ")");
            System.out.println("on_no_match: " + service.onNoMatchAction()
                    + (service.onNoMatchTopic() != null ? " -> " + service.onNoMatchTopic() : ""));
            return 1;
        }
        for (Outcome.Emission em : ((Outcome.Matched) outcome).emissions()) {
            System.out.println("rule:  " + em.rule());
            System.out.println("topic: " + em.topic());
            System.out.println("key:   " + (em.key() == null ? "(pass through input key)" : em.key()));
            System.out.println(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(em.fields()));
        }
        return 0;
    }

    private static void usage() {
        System.err.println("""
                usage: syslog-parser <command> [options]

                commands:
                  validate  --config config/service.yaml --rules config/rules.yaml
                  dry-run   --config ... --rules ... [--clock 2026-08-26T12:00:00Z] "<syslog line>"
                  test      --config ... --rules ... --tests tests/golden.yaml
                  run       --config ... --rules ...

                Option defaults: --config config/service.yaml, --rules config/rules.yaml,
                --tests tests/golden.yaml.""");
    }
}
