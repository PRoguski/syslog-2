package com.proguski.syslogparser.template;

/**
 * Render-time template failure (bad date format, null where a value is required, ...).
 * Per the design plan it is handled like a regex no-match and never stops the consumer.
 */
public class TemplateException extends RuntimeException {
    public TemplateException(String message) {
        super(message);
    }

    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
