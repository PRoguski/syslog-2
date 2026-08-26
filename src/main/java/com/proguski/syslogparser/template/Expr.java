package com.proguski.syslogparser.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal expression language used inside {{ }}:
 *
 *   expression := pipeline ( ('==' | '!=') pipeline )?
 *   pipeline   := term ( '|' IDENT [ '(' args ')' ] )*
 *   term       := 'string' | number | IDENT [ '(' args ')' ] | IDENT ('.' IDENT)+
 *   args       := expression ( ',' expression )*
 *
 * Bare identifiers are variables (regex groups, raw, kafka.*), IDENT() is a
 * function call (now()), and | applies a filter (int, lower, parse_ts(...), ...).
 */
public sealed interface Expr {

    Object eval(RenderContext ctx);

    /** Collects variable names, and filter/function usages for start-up validation. */
    void collect(Set<String> vars, List<Usage> usages);

    record Usage(String name, int argCount, boolean isFilter) {}

    record Lit(Object value) implements Expr {
        @Override public Object eval(RenderContext ctx) { return value; }
        @Override public void collect(Set<String> vars, List<Usage> usages) {}
    }

    record Var(String name) implements Expr {
        @Override public Object eval(RenderContext ctx) { return ctx.lookup(name); }
        @Override public void collect(Set<String> vars, List<Usage> usages) { vars.add(name); }
    }

    record Call(String name, List<Expr> args) implements Expr {
        @Override public Object eval(RenderContext ctx) {
            List<Object> vals = new ArrayList<>(args.size());
            for (Expr a : args) vals.add(a.eval(ctx));
            return Filters.callFunction(name, vals, ctx);
        }
        @Override public void collect(Set<String> vars, List<Usage> usages) {
            usages.add(new Usage(name, args.size(), false));
            for (Expr a : args) a.collect(vars, usages);
        }
    }

    record Filter(Expr input, String name, List<Expr> args) implements Expr {
        @Override public Object eval(RenderContext ctx) {
            Object v = input.eval(ctx);
            List<Object> vals = new ArrayList<>(args.size());
            for (Expr a : args) vals.add(a.eval(ctx));
            return Filters.applyFilter(name, v, vals, ctx);
        }
        @Override public void collect(Set<String> vars, List<Usage> usages) {
            input.collect(vars, usages);
            usages.add(new Usage(name, args.size(), true));
            for (Expr a : args) a.collect(vars, usages);
        }
    }

    record Compare(Expr left, Expr right, boolean negate) implements Expr {
        @Override public Object eval(RenderContext ctx) {
            boolean eq = valuesEqual(left.eval(ctx), right.eval(ctx));
            return negate != eq;
        }
        @Override public void collect(Set<String> vars, List<Usage> usages) {
            left.collect(vars, usages);
            right.collect(vars, usages);
        }

        private static boolean valuesEqual(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                return na.doubleValue() == nb.doubleValue();
            }
            return java.util.Objects.equals(a, b);
        }
    }

    // ------------------------------------------------------------------ parser

    static Expr parse(String source) {
        Parser p = new Parser(source);
        Expr e = p.parseExpression();
        p.expectEnd();
        return e;
    }

    final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Expr parseExpression() {
            Expr left = parsePipeline();
            skipWs();
            if (peek("==") || peek("!=")) {
                boolean negate = src.charAt(pos) == '!';
                pos += 2;
                Expr right = parsePipeline();
                return new Compare(left, right, negate);
            }
            return left;
        }

        private Expr parsePipeline() {
            Expr e = parseTerm();
            skipWs();
            while (pos < src.length() && src.charAt(pos) == '|') {
                pos++;
                skipWs();
                String name = parseIdent("filter name after '|'");
                List<Expr> args = new ArrayList<>();
                skipWs();
                if (pos < src.length() && src.charAt(pos) == '(') {
                    args = parseArgs();
                }
                e = new Filter(e, name, args);
                skipWs();
            }
            return e;
        }

        private Expr parseTerm() {
            skipWs();
            if (pos >= src.length()) {
                throw new TemplateException("unexpected end of expression: " + src);
            }
            char c = src.charAt(pos);
            if (c == '\'' || c == '"') {
                return new Lit(parseString(c));
            }
            if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length()
                    && Character.isDigit(src.charAt(pos + 1)))) {
                return new Lit(parseNumber());
            }
            if (Character.isLetter(c) || c == '_') {
                String ident = parseIdent("identifier");
                skipWs();
                if (pos < src.length() && src.charAt(pos) == '(') {
                    return new Call(ident, parseArgs());
                }
                StringBuilder dotted = new StringBuilder(ident);
                while (pos < src.length() && src.charAt(pos) == '.') {
                    pos++;
                    dotted.append('.').append(parseIdent("identifier after '.'"));
                }
                return new Var(dotted.toString());
            }
            throw new TemplateException("unexpected character '" + c + "' in expression: " + src);
        }

        private List<Expr> parseArgs() {
            pos++; // consume '('
            List<Expr> args = new ArrayList<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ')') {
                pos++;
                return args;
            }
            while (true) {
                args.add(parseExpression());
                skipWs();
                if (pos >= src.length()) {
                    throw new TemplateException("missing ')' in expression: " + src);
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ')') {
                    pos++;
                    return args;
                }
                throw new TemplateException("expected ',' or ')' in expression: " + src);
            }
        }

        private String parseString(char quote) {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '\\' && pos < src.length()) {
                    sb.append(src.charAt(pos++));
                } else if (c == quote) {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            throw new TemplateException("unterminated string in expression: " + src);
        }

        private Object parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            boolean isFloat = false;
            while (pos < src.length()
                    && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                if (src.charAt(pos) == '.') isFloat = true;
                pos++;
            }
            String num = src.substring(start, pos);
            return isFloat ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
        }

        private String parseIdent(String what) {
            skipWs();
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) {
                throw new TemplateException("expected " + what + " in expression: " + src);
            }
            return src.substring(start, pos);
        }

        private boolean peek(String s) {
            return src.startsWith(s, pos);
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        void expectEnd() {
            skipWs();
            if (pos < src.length()) {
                throw new TemplateException(
                        "unexpected trailing content \"" + src.substring(pos) + "\" in expression: " + src);
            }
        }
    }
}
