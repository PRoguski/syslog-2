package pl.example.syslogparser.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.example.syslogparser.Bootstrap;
import pl.example.syslogparser.config.ConfigError;
import pl.example.syslogparser.golden.GoldenTest;
import pl.example.syslogparser.golden.GoldenTestRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "test", description = "Run tests/golden.yaml against rules.yaml.")
public final class TestCommand implements Callable<Integer> {

    @Option(names = "--rules", required = true, description = "Path to rules.yaml")
    Path rulesPath;

    @Option(names = "--tests", required = true, description = "Path to golden.yaml")
    Path testsPath;

    @Override
    public Integer call() {
        Bootstrap.Loaded loaded;
        try {
            loaded = Bootstrap.loadRulesOnly(rulesPath);
        } catch (ConfigError e) {
            System.err.println(e.getMessage());
            return 1;
        }

        GoldenTestRunner runner = new GoldenTestRunner(loaded.engine());
        List<GoldenTest> tests = GoldenTestRunner.load(testsPath);

        int failed = 0;
        for (GoldenTest test : tests) {
            try {
                runner.assertPasses(test);
                System.out.println("PASS  " + test.name());
            } catch (AssertionError e) {
                failed++;
                System.out.println("FAIL  " + test.name());
                System.out.println("      " + e.getMessage());
            }
        }

        System.out.printf("%n%d passed, %d failed, %d total%n", tests.size() - failed, failed, tests.size());
        return failed == 0 ? 0 : 1;
    }
}
