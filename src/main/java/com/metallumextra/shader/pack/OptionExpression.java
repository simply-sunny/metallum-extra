package com.metallumextra.shader.pack;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conditions of {@code shaders.properties} ({@code program.composite3.enabled=BLOOM && QUALITY >= 2}): option names, numbers, {@code true} and
 * {@code false}, {@code !}, {@code &&}, {@code ||}, the comparisons and parentheses. A name that is not an option counts as false.
 */
public final class OptionExpression {
    private static final Pattern TOKEN = Pattern.compile("\\s*(&&|\\|\\||==|!=|<=|>=|<|>|!|\\(|\\)|[A-Za-z_][A-Za-z0-9_]*|-?\\d+(?:\\.\\d+)?)");

    private final List<String> tokens = new java.util.ArrayList<>();
    private final Map<String, String> values;
    private int at;

    private OptionExpression(final String text, final Map<String, String> values) throws PackException {
        this.values = values;
        Matcher match = TOKEN.matcher(text);
        int end = 0;
        while (match.lookingAt()) {
            tokens.add(match.group(1));
            end = match.end();
            match.region(end, text.length());
        }
        if (!text.substring(end).isBlank()) throw new PackException("cannot read the condition '" + text + "' at '" + text.substring(end).strip() + "'");
    }

    /** @param values each option's value: "true" or "false" for a toggle, the chosen value for the others */
    public static boolean evaluate(final String text, final Map<String, String> values) throws PackException {
        OptionExpression expression = new OptionExpression(text, values);
        Object result = expression.or();
        if (expression.at != expression.tokens.size()) throw new PackException("cannot read the condition '" + text + "': unexpected '" + expression.tokens.get(expression.at) + "'");
        return truthy(result);
    }

    private String peek() {
        return at < tokens.size() ? tokens.get(at) : "";
    }

    private Object or() throws PackException {
        Object left = and();
        while (peek().equals("||")) {
            at++;
            Object right = and();
            left = truthy(left) || truthy(right);
        }
        return left;
    }

    private Object and() throws PackException {
        Object left = comparison();
        while (peek().equals("&&")) {
            at++;
            Object right = comparison();
            left = truthy(left) && truthy(right);
        }
        return left;
    }

    private Object comparison() throws PackException {
        Object left = unary();
        String operator = peek();
        if (!List.of("==", "!=", "<", "<=", ">", ">=").contains(operator)) return left;
        at++;
        Object right = unary();
        Double a = number(left), b = number(right);
        if (a != null && b != null) {
            return switch (operator) {
                case "==" -> a.doubleValue() == b.doubleValue();
                case "!=" -> a.doubleValue() != b.doubleValue();
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                default -> a >= b;
            };
        }
        boolean same = String.valueOf(left).equals(String.valueOf(right));
        return switch (operator) {
            case "==" -> same;
            case "!=" -> !same;
            default -> throw new PackException("cannot compare " + left + " and " + right + " with " + operator);
        };
    }

    private Object unary() throws PackException {
        if (peek().equals("!")) {
            at++;
            return !truthy(unary());
        }
        if (peek().equals("(")) {
            at++;
            Object inside = or();
            if (!peek().equals(")")) throw new PackException("a ( in the condition is never closed");
            at++;
            return inside;
        }
        if (at >= tokens.size()) throw new PackException("the condition ends too soon");
        String token = tokens.get(at++);
        if (token.equals("true")) return true;
        if (token.equals("false")) return false;
        if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '-') return Double.parseDouble(token);
        String value = values.get(token);
        if (value == null) return false;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        Double parsed = number(value);
        return parsed != null ? (Object) parsed : value;
    }

    private static Double number(final Object value) {
        if (value instanceof Double d) return d;
        if (value instanceof Boolean) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean truthy(final Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Double d) return d != 0.0;
        return !String.valueOf(value).isEmpty();
    }
}
