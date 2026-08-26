package com.proguski.syslogparser.template;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTemplateTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);

    private RenderContext ctx(Map<String, Object> vars) {
        return new RenderContext(new HashMap<>(vars), clock);
    }

    private Object render(String template, Map<String, Object> vars) {
        return ValueTemplate.compile(template).render(ctx(vars));
    }

    @Test
    void literalValuesKeepTheirYamlType() {
        assertEquals("cisco-8000", ValueTemplate.compile("cisco-8000").render(ctx(Map.of())));
        assertEquals(42, ValueTemplate.compile(42).render(ctx(Map.of())));
        assertEquals(true, ValueTemplate.compile(true).render(ctx(Map.of())));
        assertTrue(ValueTemplate.compile("cisco-8000").isLiteral());
    }

    @Test
    void pureExpressionKeepsResultType() {
        assertEquals(402L, render("{{ pid | int }}", Map.of("pid", "402")));
        assertEquals(true, render("{{ action == 'DECLARE' }}", Map.of("action", "DECLARE")));
        assertEquals(false, render("{{ action == 'DECLARE' }}", Map.of("action", "CLEAR")));
    }

    @Test
    void interpolationConcatenatesAsString() {
        assertEquals("if=Gi0 state=up",
                render("if={{ interface }} state={{ state | lower }}",
                        Map.of("interface", "Gi0", "state", "UP")));
    }

    @Test
    void chainedFiltersAndDefaults() {
        assertEquals("", render("{{ reason | default('') }}", new HashMap<>() {{ put("reason", null); }}));
        assertEquals("timer expired", render("{{ reason | default('') }}",
                Map.of("reason", "timer expired")));
        assertEquals(3L, render("{{ pri | pri_severity }}", Map.of("pri", "187")));
    }

    @Test
    void missingVariableRendersNullInPureExpression() {
        assertNull(render("{{ nothing }}", Map.of()));
    }
}
