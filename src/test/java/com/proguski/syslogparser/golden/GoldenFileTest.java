package com.proguski.syslogparser.golden;

import com.proguski.syslogparser.config.RulesConfig;
import com.proguski.syslogparser.config.ServiceConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI gate: runs the real config (config/rules.yaml + tests/golden.yaml) so any
 * change to rules, templates or rule ordering is caught by `mvn test` too, not
 * only by `service test`.
 */
class GoldenFileTest {

    @Test
    void repositoryGoldenTestsPass() {
        Path service = Path.of("config/service.yaml");
        Path rules = Path.of("config/rules.yaml");
        Path tests = Path.of("tests/golden.yaml");
        assertTrue(Files.exists(service), "missing " + service.toAbsolutePath());

        GoldenRunner.Result result = GoldenRunner.run(
                ServiceConfig.load(service), RulesConfig.load(rules), tests);
        if (!result.ok()) {
            result.lines().forEach(System.err::println);
        }
        assertTrue(result.ok(), "golden tests failed — see stderr for details");
    }
}
