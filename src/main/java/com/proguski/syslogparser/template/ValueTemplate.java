package com.proguski.syslogparser.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One compiled template value. A YAML value without {{ }} is a literal (kept
 * with its YAML type). A value that is exactly one {{ expr }} keeps the
 * expression's type (int/bool/...); mixed text interpolates to a string.
 */
public final class ValueTemplate {

    private sealed interface Part {
        record Text(String text) implements Part {}
        record Dynamic(Expr expr) implements Part {}
    }

    private final Object literal;
    private final List<Part> parts;

    private ValueTemplate(Object literal, List<Part> parts) {
        this.literal = literal;
        this.parts = parts;
    }

    public static ValueTemplate compile(Object rawValue) {
        if (!(rawValue instanceof String s) || !s.contains("{{")) {
            return new ValueTemplate(rawValue, null);
        }
        List<Part> parts = new ArrayList<>();
        int pos = 0;
        while (pos < s.length()) {
            int open = s.indexOf("{{", pos);
            if (open < 0) {
                parts.add(new Part.Text(s.substring(pos)));
                break;
            }
            if (open > pos) {
                parts.add(new Part.Text(s.substring(pos, open)));
            }
            int close = s.indexOf("}}", open + 2);
            if (close < 0) {
                throw new TemplateException("unclosed {{ in template value: " + s);
            }
            parts.add(new Part.Dynamic(Expr.parse(s.substring(open + 2, close))));
            pos = close + 2;
        }
        return new ValueTemplate(null, parts);
    }

    public boolean isLiteral() {
        return parts == null;
    }

    public Object render(RenderContext ctx) {
        if (parts == null) {
            return literal;
        }
        if (parts.size() == 1 && parts.get(0) instanceof Part.Dynamic d) {
            return d.expr().eval(ctx); // pure expression keeps its type
        }
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            if (p instanceof Part.Text t) {
                sb.append(t.text());
            } else {
                Object v = ((Part.Dynamic) p).expr().eval(ctx);
                if (v != null) sb.append(v);
            }
        }
        return sb.toString();
    }

    /** As a string, for Kafka keys; null value stays null. */
    public String renderString(RenderContext ctx) {
        Object v = render(ctx);
        return v == null ? null : String.valueOf(v);
    }

    public void collect(Set<String> vars, List<Expr.Usage> usages) {
        if (parts == null) return;
        for (Part p : parts) {
            if (p instanceof Part.Dynamic d) {
                d.expr().collect(vars, usages);
            }
        }
    }
}
