package com.metallumextra.shader.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The expression of an {@code #if} or {@code #elif}, evaluated as the C preprocessor does: object-like macros are replaced by what they are defined as (and that
 * again), {@code defined X} and {@code defined(X)} ask whether a name is defined, a name that is not defined is 0, and the operators are the integer ones
 * ({@code + - * / % << >> & | ^ ~ ! && || == != < <= > >=} and {@code ?:}). A number with a point is kept as a float, since packs write such comparisons and the
 * graphics drivers' preprocessors accept them.
 */
public final class PreprocessorExpression {
    /** Thrown when the value depends on something the text cannot decide (a name the game defines later). */
    public static final class Open extends Exception {
        public Open(final String name) {
            super(name);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\s*(0[xX][0-9a-fA-F]+[uUlL]*|\\d+\\.\\d*(?:[eE][+-]?\\d+)?[fF]?|\\.\\d+[fF]?|\\d+[uUlL]*|[A-Za-z_]\\w*|\\|\\||&&|==|!=|<=|>=|<<|>>|[-+*/%<>!~&|^?:(),])");

    private final List<String> tokens = new ArrayList<>();
    private final Map<String, String> macros;
    private final Set<String> open;
    private int at;
    private int depth;

    private PreprocessorExpression(final List<String> tokens, final Map<String, String> macros, final Set<String> open) {
        this.tokens.addAll(tokens);
        this.macros = macros;
        this.open = open;
    }

    /**
     * @param macros the names defined, each with its replacement text (empty for a name defined with no value)
     * @param open   names whose value is not known yet: using one throws {@link Open}
     * @return whether the condition holds
     * @throws PackException if the expression cannot be read
     * @throws Open          if the answer depends on a name in {@code open}
     */
    public static boolean evaluate(final String text, final Map<String, String> macros, final Set<String> open) throws PackException, Open {
        List<String> tokens = tokenize(text);
        PreprocessorExpression expression = new PreprocessorExpression(tokens, macros, open);
        double value = expression.ternary();
        if (expression.at < expression.tokens.size()) throw new PackException("cannot read the condition '" + text + "' at '" + expression.tokens.get(expression.at) + "'");
        return value != 0.0;
    }

    private static List<String> tokenize(final String text) throws PackException {
        List<String> tokens = new ArrayList<>();
        Matcher match = TOKEN.matcher(text);
        int end = 0;
        while (match.lookingAt()) {
            tokens.add(match.group(1));
            end = match.end();
            match.region(end, text.length());
        }
        if (!text.substring(end).isBlank()) throw new PackException("cannot read the condition '" + text + "' at '" + text.substring(end).strip() + "'");
        return tokens;
    }

    private String peek() {
        return at < tokens.size() ? tokens.get(at) : "";
    }

    private boolean take(final String token) {
        if (!peek().equals(token)) return false;
        at++;
        return true;
    }

    private double ternary() throws PackException, Open {
        double condition = or();
        if (!take("?")) return condition;
        double a = ternary();
        if (!take(":")) throw new PackException("a ? has no :");
        double b = ternary();
        return condition != 0.0 ? a : b;
    }

    private double or() throws PackException, Open {
        double left = and();
        while (peek().equals("||")) {
            at++;
            double right = and();
            left = left != 0.0 || right != 0.0 ? 1 : 0;
        }
        return left;
    }

    private double and() throws PackException, Open {
        double left = bitOr();
        while (peek().equals("&&")) {
            at++;
            double right = bitOr();
            left = left != 0.0 && right != 0.0 ? 1 : 0;
        }
        return left;
    }

    private double bitOr() throws PackException, Open {
        double left = bitXor();
        while (peek().equals("|")) {
            at++;
            left = (long) left | (long) bitXor();
        }
        return left;
    }

    private double bitXor() throws PackException, Open {
        double left = bitAnd();
        while (peek().equals("^")) {
            at++;
            left = (long) left ^ (long) bitAnd();
        }
        return left;
    }

    private double bitAnd() throws PackException, Open {
        double left = equality();
        while (peek().equals("&")) {
            at++;
            left = (long) left & (long) equality();
        }
        return left;
    }

    private double equality() throws PackException, Open {
        double left = relation();
        while (true) {
            if (take("==")) left = left == relation() ? 1 : 0;
            else if (take("!=")) left = left != relation() ? 1 : 0;
            else return left;
        }
    }

    private double relation() throws PackException, Open {
        double left = shift();
        while (true) {
            if (take("<=")) left = left <= shift() ? 1 : 0;
            else if (take(">=")) left = left >= shift() ? 1 : 0;
            else if (take("<")) left = left < shift() ? 1 : 0;
            else if (take(">")) left = left > shift() ? 1 : 0;
            else return left;
        }
    }

    private double shift() throws PackException, Open {
        double left = sum();
        while (true) {
            if (take("<<")) left = (long) left << (long) sum();
            else if (take(">>")) left = (long) left >> (long) sum();
            else return left;
        }
    }

    private double sum() throws PackException, Open {
        double left = product();
        while (true) {
            if (take("+")) left += product();
            else if (take("-")) left -= product();
            else return left;
        }
    }

    private double product() throws PackException, Open {
        double left = unary();
        while (true) {
            if (take("*")) {
                left *= unary();
            } else if (take("/")) {
                double right = unary();
                if (right == 0.0) throw new PackException("division by zero in a condition");
                left = left == Math.rint(left) && right == Math.rint(right) ? (double) ((long) left / (long) right) : left / right;
            } else if (take("%")) {
                double right = unary();
                if (right == 0.0) throw new PackException("division by zero in a condition");
                left = (long) left % (long) right;
            } else {
                return left;
            }
        }
    }

    private double unary() throws PackException, Open {
        if (take("-")) return -unary();
        if (take("+")) return unary();
        if (take("!")) return unary() == 0.0 ? 1 : 0;
        if (take("~")) return ~(long) unary();
        return primary();
    }

    private double primary() throws PackException, Open {
        if (at >= tokens.size()) throw new PackException("the condition ends too soon");
        String token = tokens.get(at++);
        if (token.equals("(")) {
            double inside = ternary();
            if (!take(")")) throw new PackException("a ( in the condition is never closed");
            return inside;
        }
        char first = token.charAt(0);
        if (Character.isDigit(first) || first == '.') {
            String number = token.replaceAll("[uUlLfF]+$", "");
            if (number.startsWith("0x") || number.startsWith("0X")) return Long.parseLong(number.substring(2), 16);
            return Double.parseDouble(number);
        }
        if (!Character.isLetter(first) && first != '_') throw new PackException("cannot read the condition at '" + token + "'");
        if (token.equals("defined")) {
            boolean paren = take("(");
            if (at >= tokens.size()) throw new PackException("defined needs a name");
            String name = tokens.get(at++);
            if (paren && !take(")")) throw new PackException("a ( after defined is never closed");
            if (open.contains(name)) throw new Open(name);
            return macros.containsKey(name) ? 1 : 0;
        }
        if (token.equals("true")) return 1;
        if (token.equals("false")) return 0;
        if (open.contains(token)) throw new Open(token);
        String value = macros.get(token);
        if (value == null) return 0;
        if (value.isBlank()) return 1;
        if (++depth > 64) throw new PackException("the macro " + token + " refers to itself");
        try {
            // The macro's text is itself an expression over other macros.
            PreprocessorExpression inner = new PreprocessorExpression(tokenize(value), macros, open);
            inner.depth = depth;
            double result = inner.ternary();
            if (inner.at < inner.tokens.size()) throw new PackException("the macro " + token + " is not a number: " + value);
            return result;
        } finally {
            depth--;
        }
    }
}
