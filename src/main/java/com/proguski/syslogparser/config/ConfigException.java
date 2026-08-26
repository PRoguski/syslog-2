package com.proguski.syslogparser.config;

/** Fail-fast: any configuration problem aborts service startup with a readable message. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
