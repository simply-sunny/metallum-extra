package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * The custom uniforms and variables of {@code shaders.properties}: {@code uniform.float.rainFactor = smooth(1, rainStrength, 3, 3)} makes a uniform a
 * program may declare, and {@code variable.float.moving = if(speed > 0.0, 1, 0)} a name later expressions may use. Each is worked out every frame, in file order.
 * <p>
 * The expressions are Iris's: numbers, the standard uniforms by name ({@code eyeBrightness.y} for a component), earlier variables, {@code + - * / %},
 * comparisons, {@code && || !}, and the functions {@code if(c, a, b, ...)}, {@code in(x, a, b ...)}, {@code smooth([id,] value, fadeUp, fadeDown)}
 * (the value moves towards its target; the times are half-lives in seconds, so after that long half the way is covered) and the usual math ones.
 * Booleans are 1 and 0. Components other than the first of a vector-typed custom uniform are not supported: the types are {@code float}, {@code int}
 * and {@code bool}.
 */
public final class CustomUniforms {
    /** A custom uniform that a program may declare. */
    public record Def(String type, String name) {
    }

    private interface Node {
        double eval(Context context);
    }

    /** What an expression is evaluated against: this frame's standard values, the custom ones so far, and the time since the last frame. */
    private static final class Context {
        final Map<String, double[]> values;
        final Map<String, Double> custom;
        final double dt;
        final Map<String, Double> smoothed;

        Context(final Map<String, double[]> values, final Map<String, Double> custom, final double dt, final Map<String, Double> smoothed) {
            this.values = values;
            this.custom = custom;
            this.dt = dt;
            this.smoothed = smoothed;
        }
    }

    private record Entry(String type, String name, Node expression, boolean variable) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Double> smoothed = new HashMap<>();
    /** What names that are not uniforms mean: the pack's own constants such as {@code BIOME_PLAINS}, which the game supplies. */
    private final Map<String, Double> constants = new HashMap<>();
    private int smoothCounter;

    private CustomUniforms() {
    }

    public static CustomUniforms none() {
        return new CustomUniforms();
    }

    /**
     * @param properties {@code shaders.properties}, in file order
     * @param constant   the value of a name that is neither a uniform nor a variable (a biome), or null when it is unknown
     * @throws PackException naming the uniform whose expression cannot be read
     */
    public static CustomUniforms parse(final Map<?, ?> properties, final java.util.function.Function<String, @Nullable Double> constant) throws PackException {
        CustomUniforms result = new CustomUniforms();
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) ordered.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            String key = entry.getKey();
            boolean variable = key.startsWith("variable.");
            if (!variable && !key.startsWith("uniform.")) continue;
            String[] parts = key.split("\\.", 3);
            if (parts.length != 3) continue;
            String type = parts[1];
            if (!List.of("float", "int", "bool").contains(type)) throw new PackException(key + ": the type " + type + " is not supported (float, int and bool are)");
            try {
                Parser parser = new Parser(entry.getValue(), result, constant);
                Node node = parser.parse();
                result.entries.add(new Entry(type, parts[2], node, variable));
            } catch (PackException e) {
                throw new PackException(key + " = " + entry.getValue() + ": " + e.getMessage(), e);
            }
        }
        return result;
    }

    /** The uniforms (not the variables) a program may declare. */
    public List<Def> uniforms() {
        List<Def> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.variable) result.add(new Def(entry.type, entry.name));
        }
        return result;
    }

    /** Works out this frame's values and adds the uniforms' to {@code values}. */
    public void evaluate(final Map<String, double[]> values, final double dt) {
        Map<String, Double> custom = new HashMap<>();
        Context context = new Context(values, custom, dt, smoothed);
        for (Entry entry : entries) {
            double value = entry.expression.eval(context);
            if (entry.type.equals("int")) value = Math.floor(value);
            else if (entry.type.equals("bool")) value = value != 0.0 ? 1.0 : 0.0;
            custom.put(entry.name, value);
            if (!entry.variable) values.put(entry.name, new double[] {value});
        }
    }

    // ---- the expression language ----

    private static final Map<String, DoubleUnaryOperator> UNARY = Map.ofEntries(
            Map.entry("abs", Math::abs), Map.entry("sqrt", Math::sqrt), Map.entry("floor", Math::floor), Map.entry("ceil", Math::ceil),
            Map.entry("round", v -> (double) Math.round(v)), Map.entry("exp", Math::exp), Map.entry("log", Math::log), Map.entry("log10", Math::log10),
            Map.entry("sin", Math::sin), Map.entry("cos", Math::cos), Map.entry("tan", Math::tan), Map.entry("asin", Math::asin), Map.entry("acos", Math::acos),
            Map.entry("atan", Math::atan), Map.entry("radians", Math::toRadians), Map.entry("degrees", Math::toDegrees), Map.entry("signum", Math::signum),
            Map.entry("frac", v -> v - Math.floor(v)), Map.entry("fract", v -> v - Math.floor(v)), Map.entry("saturate", v -> Math.max(0.0, Math.min(1.0, v))));
    private static final Map<String, DoubleBinaryOperator> BINARY = Map.of("min", Math::min, "max", Math::max, "pow", Math::pow, "atan2", Math::atan2, "mod", (a, b) -> a - b * Math.floor(a / b));

    private static final class Parser {
        private final String text;
        private int at;
        private final CustomUniforms owner;
        private final java.util.function.Function<String, @Nullable Double> constant;

        Parser(final String text, final CustomUniforms owner, final java.util.function.Function<String, @Nullable Double> constant) {
            this.text = text;
            this.owner = owner;
            this.constant = constant;
        }

        Node parse() throws PackException {
            Node node = or();
            skip();
            if (at < text.length()) throw new PackException("cannot read it at '" + text.substring(at) + "'");
            return node;
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private boolean take(final String token) {
            skip();
            if (!text.startsWith(token, at)) return false;
            // A single < or > must not eat the first half of <= or >=; | and & only come in pairs.
            at += token.length();
            return true;
        }

        private boolean peekIs(final String token) {
            skip();
            return text.startsWith(token, at);
        }

        private Node or() throws PackException {
            Node left = and();
            while (take("||")) {
                Node a = left;
                Node b = and();
                left = c -> a.eval(c) != 0.0 || b.eval(c) != 0.0 ? 1.0 : 0.0;
            }
            return left;
        }

        private Node and() throws PackException {
            Node left = equality();
            while (take("&&")) {
                Node a = left;
                Node b = equality();
                left = c -> a.eval(c) != 0.0 && b.eval(c) != 0.0 ? 1.0 : 0.0;
            }
            return left;
        }

        private Node equality() throws PackException {
            Node left = relation();
            while (true) {
                if (take("==")) {
                    Node a = left;
                    Node b = relation();
                    left = c -> a.eval(c) == b.eval(c) ? 1.0 : 0.0;
                } else if (take("!=")) {
                    Node a = left;
                    Node b = relation();
                    left = c -> a.eval(c) != b.eval(c) ? 1.0 : 0.0;
                } else {
                    return left;
                }
            }
        }

        private Node relation() throws PackException {
            Node left = sum();
            while (true) {
                Node a = left;
                if (take("<=")) {
                    Node b = sum();
                    left = c -> a.eval(c) <= b.eval(c) ? 1.0 : 0.0;
                } else if (take(">=")) {
                    Node b = sum();
                    left = c -> a.eval(c) >= b.eval(c) ? 1.0 : 0.0;
                } else if (!peekIs("<<") && take("<")) {
                    Node b = sum();
                    left = c -> a.eval(c) < b.eval(c) ? 1.0 : 0.0;
                } else if (!peekIs(">>") && take(">")) {
                    Node b = sum();
                    left = c -> a.eval(c) > b.eval(c) ? 1.0 : 0.0;
                } else {
                    return left;
                }
            }
        }

        private Node sum() throws PackException {
            Node left = product();
            while (true) {
                Node a = left;
                if (take("+")) {
                    Node b = product();
                    left = c -> a.eval(c) + b.eval(c);
                } else if (take("-")) {
                    Node b = product();
                    left = c -> a.eval(c) - b.eval(c);
                } else {
                    return left;
                }
            }
        }

        private Node product() throws PackException {
            Node left = unary();
            while (true) {
                Node a = left;
                if (take("*")) {
                    Node b = unary();
                    left = c -> a.eval(c) * b.eval(c);
                } else if (take("/")) {
                    Node b = unary();
                    left = c -> {
                        double d = b.eval(c);
                        return d == 0.0 ? 0.0 : a.eval(c) / d;
                    };
                } else if (take("%")) {
                    Node b = unary();
                    left = c -> {
                        double d = b.eval(c);
                        return d == 0.0 ? 0.0 : a.eval(c) % d;
                    };
                } else {
                    return left;
                }
            }
        }

        private Node unary() throws PackException {
            if (take("-")) {
                Node a = unary();
                return c -> -a.eval(c);
            }
            if (!peekIs("!=") && take("!")) {
                Node a = unary();
                return c -> a.eval(c) == 0.0 ? 1.0 : 0.0;
            }
            return primary();
        }

        private Node primary() throws PackException {
            skip();
            if (at >= text.length()) throw new PackException("it ends too soon");
            char ch = text.charAt(at);
            if (ch == '(') {
                at++;
                Node inside = or();
                if (!take(")")) throw new PackException("a ( is never closed");
                return inside;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = at;
                while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) at++;
                if (at < text.length() && (text.charAt(at) == 'f' || text.charAt(at) == 'F')) at++;
                double number = Double.parseDouble(text.substring(start, at).replaceAll("[fF]$", ""));
                return c -> number;
            }
            if (!Character.isLetter(ch) && ch != '_') throw new PackException("cannot read it at '" + text.substring(at) + "'");
            int start = at;
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) at++;
            String name = text.substring(start, at);
            skip();
            if (at < text.length() && text.charAt(at) == '(') {
                at++;
                List<Node> arguments = new ArrayList<>();
                if (!take(")")) {
                    do {
                        arguments.add(or());
                    } while (take(","));
                    if (!take(")")) throw new PackException("a ( after " + name + " is never closed");
                }
                return function(name, arguments);
            }
            String component = null;
            if (at < text.length() && text.charAt(at) == '.' && at + 1 < text.length() && Character.isLetter(text.charAt(at + 1))) {
                at++;
                int s = at;
                while (at < text.length() && Character.isLetter(text.charAt(at))) at++;
                component = text.substring(s, at);
            }
            return reference(name, component);
        }

        private Node reference(final String name, final @Nullable String component) throws PackException {
            int index = component == null ? 0 : "xyzw".indexOf(component.charAt(0)) >= 0 ? "xyzw".indexOf(component.charAt(0)) : "rgba".indexOf(component.charAt(0));
            if (component != null && (component.length() != 1 || index < 0)) throw new PackException("the component ." + component + " is not x, y, z or w");
            switch (name) {
                case "true":
                    return c -> 1.0;
                case "false":
                    return c -> 0.0;
                case "PI":
                    return c -> Math.PI;
                default:
                    break;
            }
            // A name that is neither a uniform nor an earlier variable is one of the constants the game supplies (a biome): asked when it is used, because
            // the game's registries may not exist yet when the pack is read.
            return c -> {
                Double custom = c.custom.get(name);
                if (custom != null) return custom;
                double[] value = c.values.get(name);
                if (value != null) return index < value.length ? value[index] : 0.0;
                Double fixed = owner.constants.get(name);
                if (fixed == null) {
                    fixed = constant.apply(name);
                    if (fixed != null) owner.constants.put(name, fixed);
                }
                return fixed == null ? 0.0 : fixed;
            };
        }

        private Node function(final String name, final List<Node> a) throws PackException {
            DoubleUnaryOperator unary = UNARY.get(name);
            if (unary != null && a.size() == 1) return c -> unary.applyAsDouble(a.get(0).eval(c));
            DoubleBinaryOperator binary = BINARY.get(name);
            if (binary != null && a.size() == 2) return c -> binary.applyAsDouble(a.get(0).eval(c), a.get(1).eval(c));
            switch (name) {
                case "if" -> {
                    // if(cond, then, [cond2, then2, ...] else)
                    if (a.size() < 3 || a.size() % 2 == 0) throw new PackException("if needs a condition, a value, and an else value");
                    return c -> {
                        for (int i = 0; i + 1 < a.size(); i += 2) {
                            if (a.get(i).eval(c) != 0.0) return a.get(i + 1).eval(c);
                        }
                        return a.get(a.size() - 1).eval(c);
                    };
                }
                case "in" -> {
                    if (a.size() < 2) throw new PackException("in needs a value and the values to look for");
                    return c -> {
                        double value = a.get(0).eval(c);
                        for (int i = 1; i < a.size(); i++) {
                            if (a.get(i).eval(c) == value) return 1.0;
                        }
                        return 0.0;
                    };
                }
                case "clamp" -> {
                    if (a.size() != 3) throw new PackException("clamp needs a value, a minimum and a maximum");
                    return c -> Math.max(a.get(1).eval(c), Math.min(a.get(2).eval(c), a.get(0).eval(c)));
                }
                case "lerp", "mix" -> {
                    if (a.size() != 3) throw new PackException(name + " needs two values and an amount");
                    return c -> {
                        double t = a.get(2).eval(c);
                        return a.get(0).eval(c) * (1.0 - t) + a.get(1).eval(c) * t;
                    };
                }
                case "smooth" -> {
                    // smooth(id, value, fadeUp, fadeDown) with an id, or smooth(value, fadeUp, fadeDown) without. The state is the id's: two calls with the same id share it.
                    if (a.isEmpty() || a.size() > 4) throw new PackException("smooth needs an id, a value and the two fade times");
                    boolean withId = a.size() == 4;
                    Node id = withId ? a.get(0) : null;
                    int anonymous = owner.smoothCounter++;
                    Node value = withId ? a.get(1) : a.get(0);
                    Node up = a.size() > (withId ? 2 : 1) ? a.get(withId ? 2 : 1) : null;
                    Node down = a.size() > (withId ? 3 : 2) ? a.get(withId ? 3 : 2) : up;
                    return c -> {
                        String key = id != null ? "id" + (long) id.eval(c) : "call" + anonymous;
                        double target = value.eval(c);
                        double current = c.smoothed.getOrDefault(key, target);
                        double halfLife = target > current ? (up == null ? 0.0 : up.eval(c)) : (down == null ? 0.0 : down.eval(c));
                        double next = halfLife <= 0.0 ? target : current + (target - current) * (1.0 - Math.pow(0.5, c.dt / halfLife));
                        c.smoothed.put(key, next);
                        return next;
                    };
                }
                default -> throw new PackException("the function " + name + " is not supported");
            }
        }
    }
}
