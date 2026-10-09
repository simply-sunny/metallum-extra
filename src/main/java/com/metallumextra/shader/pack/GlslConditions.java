package com.metallumextra.shader.pack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the parts of a shader out that the preprocessor would not compile, so that what the loader looks for in it (samplers, vertex inputs,
 * outputs) is only what is really there. The text is not otherwise changed, and the lines stay (blank) so that numbers in messages stay right.
 * <p>
 * Only what can be decided from the file is decided: a condition on a name that no line defines and that is not an option (the game defines some
 * for the pipeline it compiles a program for, such as {@code EMISSIVE}) is left open, and both of its sides are kept.
 */
public final class GlslConditions {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(ifdef|ifndef|if|elif|else|endif|define|undef)\\b(.*)$");
    private static final Pattern DEFINED = Pattern.compile("defined\\s*\\(\\s*(\\w+)\\s*\\)|defined\\s+(\\w+)");

    private GlslConditions() {
    }

    /** One level of nesting: whether its current branch is kept, and whether any earlier branch of it was taken (so an else is not). */
    private static final class Level {
        boolean parentKept;
        boolean kept;
        boolean taken;
        boolean open;
    }

    /**
     * @param options the names of the pack's options: each is known, and defined or not as the text says
     */
    public static String strip(final String source, final Set<String> options) {
        Map<String, String> defined = new HashMap<>();
        // Names the text defines anywhere are known; an option that no line defines is known to be off.
        Matcher all = Pattern.compile("(?m)^\\s*#\\s*define\\s+(\\w+)\\s*(.*?)\\s*(?://.*)?$").matcher(source);
        Set<String> known = new java.util.HashSet<>(options);
        while (all.find()) known.add(all.group(1));

        StringBuilder out = new StringBuilder(source.length());
        Deque<Level> stack = new ArrayDeque<>();
        boolean kept = true;
        for (String line : source.split("\n", -1)) {
            Matcher match = DIRECTIVE.matcher(line);
            String kind = match.matches() ? match.group(1) : "";
            String rest = match.matches() ? match.group(2).strip() : "";
            switch (kind) {
                case "ifdef", "ifndef", "if" -> {
                    Level level = new Level();
                    level.parentKept = kept;
                    Boolean value = condition(kind, rest, defined, known);
                    level.open = value == null;
                    level.kept = value == null || value;
                    level.taken = value != null && value;
                    stack.push(level);
                    kept = level.parentKept && level.kept;
                }
                case "elif" -> {
                    Level level = stack.peek();
                    if (level != null) {
                        Boolean value = level.taken ? Boolean.FALSE : condition("if", rest, defined, known);
                        level.open |= value == null;
                        level.kept = value == null || value;
                        level.taken |= value != null && value;
                        kept = level.parentKept && level.kept;
                    }
                }
                case "else" -> {
                    Level level = stack.peek();
                    if (level != null) {
                        level.kept = level.open || !level.taken;
                        kept = level.parentKept && level.kept;
                    }
                }
                case "endif" -> {
                    Level level = stack.poll();
                    if (level != null) kept = level.parentKept;
                }
                case "define" -> {
                    if (kept) {
                        Matcher d = Pattern.compile("(\\w+)\\s*(.*?)\\s*(?://.*)?$").matcher(rest);
                        if (d.matches()) defined.put(d.group(1), d.group(2));
                    }
                }
                case "undef" -> {
                    if (kept) defined.remove(rest);
                }
                default -> {
                }
            }
            out.append(kept && (kind.isEmpty() || kind.equals("define")) ? line : "").append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }

    /** True or false when the condition can be decided here, null when it depends on something the file does not say. */
    private static Boolean condition(final String kind, final String text, final Map<String, String> defined, final Set<String> known) {
        switch (kind) {
            case "ifdef":
                return known.contains(text) ? Boolean.valueOf(defined.containsKey(text)) : null;
            case "ifndef":
                return known.contains(text) ? Boolean.valueOf(!defined.containsKey(text)) : null;
            default:
                break;
        }
        String expression = text.replaceAll("//.*$", "").strip();
        Matcher match = DEFINED.matcher(expression);
        StringBuilder replaced = new StringBuilder();
        while (match.find()) {
            String name = match.group(1) != null ? match.group(1) : match.group(2);
            if (!known.contains(name)) return null;
            match.appendReplacement(replaced, defined.containsKey(name) ? "true" : "false");
        }
        match.appendTail(replaced);
        // Names the text defines have their values; a name it does not know leaves the condition open.
        Map<String, String> values = new HashMap<>();
        Matcher names = Pattern.compile("[A-Za-z_]\\w*").matcher(replaced);
        while (names.find()) {
            String name = names.group();
            if (name.equals("true") || name.equals("false")) continue;
            if (!defined.containsKey(name)) return null;
            String value = defined.get(name);
            values.put(name, value.isEmpty() ? "true" : value);
        }
        try {
            return OptionExpression.evaluate(replaced.toString(), values);
        } catch (PackException e) {
            return null;
        }
    }
}
