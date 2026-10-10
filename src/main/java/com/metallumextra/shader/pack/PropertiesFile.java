package com.metallumextra.shader.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code shaders.properties} (and the other {@code .properties} files of a pack) the way Iris does: the file goes through a preprocessor first,
 * so {@code #if}, {@code #ifdef}, {@code #elif}, {@code #else}, {@code #endif}, {@code #define} and {@code #undef} choose which lines count, by the
 * pack's options and {@link ShaderMacros}; a name no one defined is 0. Then each line is {@code key=value}; a line ending in {@code \} goes on in the next;
 * a line starting with {@code #} or {@code !} that is not a directive is a comment. Unlike {@link java.util.Properties}, keys stay in file order.
 */
public final class PropertiesFile {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(ifdef|ifndef|if|elif|else|endif|define|undef)\\b(.*)$");
    private static final Pattern DEFINED = Pattern.compile("defined\\s*\\(\\s*(\\w+)\\s*\\)|defined\\s+(\\w+)");

    private PropertiesFile() {
    }

    private static final class Level {
        boolean parentKept;
        boolean kept;
        boolean taken;
    }

    /**
     * @param macros the names defined at the start (options, {@link ShaderMacros}): a name to its value, which may be empty
     * @return the keys and values, in file order
     */
    public static Map<String, String> parse(final String text, final Map<String, String> macros) {
        Map<String, String> defined = new HashMap<>(macros);
        Map<String, String> result = new LinkedHashMap<>();
        java.util.ArrayDeque<Level> stack = new java.util.ArrayDeque<>();
        boolean kept = true;
        List<String> lines = joined(text);
        for (String line : lines) {
            Matcher match = DIRECTIVE.matcher(line);
            if (match.matches()) {
                String rest = match.group(2).replaceAll("//.*$", "").strip();
                switch (match.group(1)) {
                    case "ifdef", "ifndef", "if" -> {
                        Level level = new Level();
                        level.parentKept = kept;
                        boolean value = match.group(1).equals("ifdef") ? defined.containsKey(rest) : match.group(1).equals("ifndef") ? !defined.containsKey(rest) : condition(rest, defined);
                        level.kept = value;
                        level.taken = value;
                        stack.push(level);
                        kept = level.parentKept && level.kept;
                    }
                    case "elif" -> {
                        Level level = stack.peek();
                        if (level != null) {
                            boolean value = !level.taken && condition(rest, defined);
                            level.kept = value;
                            level.taken |= value;
                            kept = level.parentKept && value;
                        }
                    }
                    case "else" -> {
                        Level level = stack.peek();
                        if (level != null) {
                            level.kept = !level.taken;
                            level.taken = true;
                            kept = level.parentKept && level.kept;
                        }
                    }
                    case "endif" -> {
                        Level level = stack.poll();
                        if (level != null) kept = level.parentKept;
                    }
                    case "define" -> {
                        if (kept) {
                            String[] parts = rest.split("\\s+", 2);
                            if (!parts[0].isEmpty()) defined.put(parts[0], parts.length > 1 ? parts[1].strip() : "");
                        }
                    }
                    case "undef" -> {
                        if (kept) defined.remove(rest);
                    }
                    default -> {
                    }
                }
                continue;
            }
            if (!kept) continue;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int equals = trimmed.indexOf('=');
            if (equals <= 0) continue;
            result.put(trimmed.substring(0, equals).strip(), trimmed.substring(equals + 1).strip());
        }
        return result;
    }

    private static List<String> joined(final String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder pending = null;
        for (String line : text.split("\\r?\\n", -1)) {
            String piece = line;
            boolean more = piece.stripTrailing().endsWith("\\");
            if (more) piece = piece.stripTrailing().substring(0, piece.stripTrailing().length() - 1);
            if (pending == null) pending = new StringBuilder(piece);
            else pending.append(piece.stripLeading());
            if (!more) {
                lines.add(pending.toString());
                pending = null;
            }
        }
        if (pending != null) lines.add(pending.toString());
        return lines;
    }

    /** An {@code #if}: a name is its value (a number), or 0; {@code defined} tests for a name. A condition that cannot be read is false. */
    private static boolean condition(final String text, final Map<String, String> defined) {
        try {
            return PreprocessorExpression.evaluate(text, defined, java.util.Set.of());
        } catch (PreprocessorExpression.Open | PackException e) {
            return false;
        }
    }

    /** The macros the options of a pack define: a toggle that is on is defined as 1, a toggle that is off is not, any other option has its value. */
    public static Map<String, String> macros(final Map<String, String> optionValues) {
        Map<String, String> macros = new LinkedHashMap<>(ShaderMacros.builtin());
        for (Map.Entry<String, String> entry : optionValues.entrySet()) {
            String value = entry.getValue();
            if (value.equals("false")) continue;
            macros.put(entry.getKey(), value.equals("true") ? "1" : value);
        }
        return macros;
    }
}
