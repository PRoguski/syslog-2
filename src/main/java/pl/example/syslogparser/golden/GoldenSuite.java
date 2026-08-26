package pl.example.syslogparser.golden;

import java.util.List;

/** Root of {@code tests/golden.yaml}. */
public record GoldenSuite(List<GoldenTest> tests) {

    public GoldenSuite {
        if (tests == null) {
            tests = List.of();
        }
    }
}
