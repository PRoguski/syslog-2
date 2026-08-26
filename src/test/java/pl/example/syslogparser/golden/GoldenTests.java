package pl.example.syslogparser.golden;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import pl.example.syslogparser.Bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Runs every case in {@code tests/golden.yaml} against {@code config/rules.yaml}
 * as a JUnit test each, so a broken regex or a reordered catch-all shows up as
 * a named test failure in CI, not just in the CLI {@code test} subcommand.
 */
class GoldenTests {

    @TestFactory
    Stream<DynamicTest> golden() {
        Bootstrap.Loaded loaded = Bootstrap.loadRulesOnly(Path.of("config/rules.yaml"));
        GoldenTestRunner runner = new GoldenTestRunner(loaded.engine());
        List<GoldenTest> tests = GoldenTestRunner.load(Path.of("tests/golden.yaml"));

        return tests.stream().map(t -> dynamicTest(t.name(), () -> runner.assertPasses(t)));
    }
}
