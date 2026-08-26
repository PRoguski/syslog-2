package pl.example.syslogparser;

import pl.example.syslogparser.config.ConfigLoader;
import pl.example.syslogparser.config.ConfigValidator;
import pl.example.syslogparser.config.RulesConfig;
import pl.example.syslogparser.config.ServiceConfig;
import pl.example.syslogparser.engine.RuleEngine;
import pl.example.syslogparser.expr.Filters;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Loads and validates configuration, then wires up a {@link RuleEngine}.
 * Shared by every CLI subcommand and by the JUnit golden test factory, so
 * "does the config load" and "does the engine behave" are never checked two
 * different ways.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public record Loaded(ServiceConfig service, RulesConfig rulesConfig, ConfigValidator.Result rules,
            RuleEngine engine) {
    }

    /** Full load: {@code service.yaml} + {@code rules.yaml}, used by {@code validate} and {@code run}. */
    public static Loaded load(Path servicePath, Path rulesPath) {
        ServiceConfig service = ConfigLoader.loadService(servicePath);
        RulesConfig rulesConfig = ConfigLoader.loadRules(rulesPath);
        Filters filters = new Filters();
        ConfigValidator validator = new ConfigValidator(filters);
        validator.validateService(service);
        ConfigValidator.Result result = validator.validateRules(rulesConfig);
        RuleEngine.Strategy strategy = RuleEngine.strategyOf(service.routing().strategy());
        RuleEngine engine = new RuleEngine(result.rules(), strategy, Clock.systemUTC());
        return new Loaded(service, rulesConfig, result, engine);
    }

    /** Rules-only load, used by {@code dry-run} and {@code test}: no Kafka connectivity needed. */
    public static Loaded loadRulesOnly(Path rulesPath) {
        RulesConfig rulesConfig = ConfigLoader.loadRules(rulesPath);
        Filters filters = new Filters();
        ConfigValidator validator = new ConfigValidator(filters);
        ConfigValidator.Result result = validator.validateRules(rulesConfig);
        RuleEngine engine = new RuleEngine(result.rules(), RuleEngine.Strategy.FIRST_MATCH, Clock.systemUTC());
        return new Loaded(null, rulesConfig, result, engine);
    }
}
