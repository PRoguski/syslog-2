package pl.example.syslogparser.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads {@code service.yaml} / {@code rules.yaml} into records.
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is enabled on purpose: a typo such as
 * {@code outputs:} instead of {@code output:} must abort startup with a clear
 * error, not silently vanish.
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private ConfigLoader() {
    }

    public static ServiceConfig loadService(Path path) {
        return load(path, ServiceConfig.class);
    }

    public static RulesConfig loadRules(Path path) {
        return load(path, RulesConfig.class);
    }

    private static <T> T load(Path path, Class<T> type) {
        try {
            return MAPPER.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new ConfigError("cannot load " + path + ": " + e.getMessage(), e);
        }
    }
}
