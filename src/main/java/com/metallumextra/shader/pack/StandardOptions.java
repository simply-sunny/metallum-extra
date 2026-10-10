package com.metallumextra.shader.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The options of a standard (Iris layout) pack, which are found in its shader files and not listed anywhere else:
 * <ul>
 * <li>{@code #define NAME value // [a b c]} is a choice among the listed values; the value in the line is the default,</li>
 * <li>{@code const float NAME = value; // [a b c]} the same for a constant the program reads (shadow resolution ...),</li>
 * <li>{@code #define NAME} and {@code //#define NAME} are a toggle, on and off by default.</li>
 * </ul>
 * What the player chose (see {@link PackOptions}) is written into the files as they are read: a chosen value replaces the one in its line, and a toggle
 * is switched by commenting its line out or in. Labels come from the pack's {@code lang/en_us.lang} ({@code option.NAME=Label}).
 */
public final class StandardOptions {
    private static final Pattern VALUE = Pattern.compile("(?m)^[ \\t]*#define[ \\t]+([A-Za-z_]\\w*)[ \\t]+(\\S+)[ \\t]*//[ \\t]*\\[([^\\]\\n]*)\\]");
    private static final Pattern CONSTANT = Pattern.compile("(?m)^[ \\t]*const[ \\t]+(?:int|float|bool)[ \\t]+([A-Za-z_]\\w*)[ \\t]*=[ \\t]*([^;\\n]+);[ \\t]*//[ \\t]*\\[([^\\]\\n]*)\\]");
    private static final Pattern TOGGLE = Pattern.compile("(?m)^[ \\t]*(//[ \\t]*)?#define[ \\t]+([A-Z][A-Z0-9_]*)[ \\t]*(?://.*)?$");
    private static final Pattern LABEL = Pattern.compile("(?m)^option\\.([A-Za-z_]\\w*)[ \\t]*=[ \\t]*(.+)$");
    private static final List<String> EXTENSIONS = List.of(".fsh", ".vsh", ".glsl", ".inc", ".h", ".gsh", ".csh");

    private StandardOptions() {
    }

    /** The options in these files, in the order they are found; {@code files} maps each path to its text. */
    public static List<PackOption> discover(final Map<String, String> files) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String path : List.of("lang/en_us.lang", "lang/en_US.lang")) {
            String lang = files.get(path);
            if (lang == null) continue;
            Matcher match = LABEL.matcher(lang);
            while (match.find()) labels.putIfAbsent(match.group(1), match.group(2).strip());
        }
        Map<String, PackOption> found = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>(files.keySet());
        java.util.Collections.sort(paths);
        for (String path : paths) {
            if (EXTENSIONS.stream().noneMatch(path::endsWith)) continue;
            String text = files.get(path);
            Matcher match = VALUE.matcher(text);
            while (match.find()) add(found, labels, match.group(1), match.group(2), match.group(3));
            match = CONSTANT.matcher(text);
            while (match.find()) add(found, labels, match.group(1), match.group(2).strip(), match.group(3));
            match = TOGGLE.matcher(text);
            while (match.find()) {
                String name = match.group(2);
                if (name.endsWith("_GLSL") || name.endsWith("_H") || name.endsWith("_INC") || found.containsKey(name)) continue;
                found.put(name, new PackOption(name, labels.getOrDefault(name, name), List.of("Off", "On"), match.group(1) == null ? 1 : 0, true));
            }
        }
        return List.copyOf(found.values());
    }

    private static void add(final Map<String, PackOption> found, final Map<String, String> labels, final String name, final String value, final String list) {
        if (found.containsKey(name)) return;
        List<String> values = new ArrayList<>(List.of(list.strip().split("\\s+")));
        values.removeIf(String::isEmpty);
        if (values.isEmpty()) return;
        int at = values.indexOf(value);
        if (at < 0) {
            values.add(0, value);
            at = 0;
        }
        found.put(name, new PackOption(name, labels.getOrDefault(name, name), List.copyOf(values), at, false));
    }

    private static final Pattern TOGGLE_LINE = Pattern.compile("^([ \\t]*)(?://[ \\t]*)?#define[ \\t]+([A-Za-z_]\\w*)\\b[ \\t]*((?://.*)?)$");
    private static final Pattern VALUE_LINE = Pattern.compile("^([ \\t]*#define[ \\t]+)([A-Za-z_]\\w*)([ \\t]+)\\S+");
    private static final Pattern CONSTANT_LINE = Pattern.compile("(const[ \\t]+(?:int|float|bool)[ \\t]+)([A-Za-z_]\\w*)([ \\t]*=[ \\t]*)[^;\\n]+;");

    /**
     * The text with the player's choices written in. One pass over the lines: a pack has hundreds of options and its programs thousands of lines, so
     * looking for each option in the whole text would take longer than compiling the program.
     */
    public static String apply(final String text, final String pack, final List<PackOption> options) {
        Map<String, PackOption> byName = new java.util.HashMap<>();
        Map<String, Integer> chosen = new java.util.HashMap<>();
        for (PackOption option : options) {
            byName.put(option.id(), option);
            chosen.put(option.id(), PackOptions.get(pack, option));
        }
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            out.append(line(text.substring(start, end), byName, chosen));
            if (end < text.length()) out.append('\n');
            start = end + 1;
        }
        return out.toString();
    }

    private static String line(final String line, final Map<String, PackOption> byName, final Map<String, Integer> chosen) {
        if (line.indexOf("define") < 0 && line.indexOf("const") < 0) return line;
        Matcher match = TOGGLE_LINE.matcher(line);
        if (match.matches()) {
            PackOption option = byName.get(match.group(2));
            if (option != null && option.toggle()) {
                boolean on = chosen.get(option.id()) == 1;
                return match.group(1) + (on ? "" : "//") + "#define " + option.id() + (match.group(3).isEmpty() ? "" : " " + match.group(3));
            }
            return line;
        }
        String result = line;
        match = VALUE_LINE.matcher(result);
        if (match.find()) {
            PackOption option = byName.get(match.group(2));
            if (option != null && !option.toggle()) {
                result = match.group(1) + match.group(2) + match.group(3) + option.values().get(chosen.get(option.id())) + result.substring(match.end());
            }
        }
        match = CONSTANT_LINE.matcher(result);
        StringBuilder replaced = new StringBuilder();
        while (match.find()) {
            PackOption option = byName.get(match.group(2));
            String replacement = option != null && !option.toggle() ? match.group(1) + match.group(2) + match.group(3) + option.values().get(chosen.get(option.id())) + ";" : match.group();
            match.appendReplacement(replaced, Matcher.quoteReplacement(replacement));
        }
        match.appendTail(replaced);
        return replaced.toString();
    }

    /** What each option is set to, for the conditions of {@code shaders.properties}: "true" or "false" for a toggle, the value for the others. */
    public static Map<String, String> values(final String pack, final List<PackOption> options) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PackOption option : options) {
            int chosen = PackOptions.get(pack, option);
            result.put(option.id(), option.toggle() ? String.valueOf(chosen == 1) : option.values().get(chosen));
        }
        return result;
    }
}
