package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A complete set of shader files in the Iris layout. Paths are relative to the pack's {@code shaders/} folder:
 * {@code world0/final.fsh}, {@code shaders.properties}, {@code lib/lighting.glsl}.
 * <p>
 * A pack never falls back to another one: what it does not contain is missing.
 */
public interface ShaderPack {
    /** The file's text, or null if the pack has no such file. */
    @Nullable String read(String path);

    /** The bytes of a file that is not text (a picture), or null if the pack has none. */
    default byte @Nullable [] bytes(final String path) {
        return null;
    }

    /** What the player sees in the menu. */
    String name();

    /** Every file of the pack, by path, for discovering programs. */
    Set<String> files();

    /** The settings this pack offers (see {@link StandardOptions}), in the order they are found. Most packs have a few. */
    default List<PackOption> options() {
        return List.of();
    }

    /**
     * The file with every {@code #include "name.glsl"} replaced by the file it names, which counts from the {@code shaders/} folder when the name
     * starts with {@code /} and from the folder of the file that includes it otherwise, as in Iris. A file goes in once per shader, however many
     * of the files it includes ask for it. The pack's options, as the player set them, are written into the text, and a program of the old
     * compatibility profile of GLSL is translated (see {@link LegacyGlsl}).
     *
     * @throws IllegalStateException if the file or something it includes is not in the pack
     */
    default String load(final String path) {
        String text = read(path);
        if (text == null) throw new IllegalStateException("Shader pack " + name() + " has no " + path);
        StringBuilder out = new StringBuilder(text.length() + 4096);
        expand(text, path, out, new HashSet<>());
        List<PackOption> options = options();
        String text2 = options.isEmpty() ? out.toString() : StandardOptions.apply(out.toString(), name(), options);
        boolean vertex = path.endsWith(".vsh");
        if (!vertex && !path.endsWith(".fsh")) return text2;
        // The branches the options and macros decide are decided now, so that nothing downstream reads code the compiler would never see.
        Set<String> optionNames = new HashSet<>();
        for (PackOption option : options) optionNames.add(option.id());
        text2 = GlslConditions.decide(withMacros(LegacyGlsl.spliceLines(text2)), optionNames);
        try {
            // A program of the old compatibility profile of GLSL is read in the names of Iris from here on (see LegacyGlsl).
            LegacyGlsl.Kind kind = LegacyGlsl.Kind.of(path);
            // Iris offers the block atlas as gtexture, and also as tex and texture (the old name, which is a GLSL function once texture2D becomes texture(): renamed before that).
            if (kind == LegacyGlsl.Kind.WORLD) text2 = LegacyGlsl.renameSampler(text2, "texture", "gtexture");
            String translated = LegacyGlsl.translate(text2, vertex, kind);
            if (kind == LegacyGlsl.Kind.WORLD) translated = LegacyGlsl.renameSampler(translated, "tex", "gtexture");
            return IrisUniforms.zeroUndefined(ProgramSet.withoutUnusedUniforms(LegacyGlsl.cppKeywords(LegacyGlsl.shadowSamplers(translated))));
        } catch (PackException e) {
            throw new IllegalStateException(path + ": " + e.getMessage(), e);
        }
    }

    /** The text with {@link ShaderMacros} defined right after its {@code #version} line (as Iris does), or at the top without one. */
    private static String withMacros(final String text) {
        Matcher version = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[^\\n]*\\n").matcher(text);
        if (!version.find()) return ShaderMacros.glsl() + text;
        return text.substring(0, version.end()) + ShaderMacros.glsl() + text.substring(version.end());
    }

    private String includePath(final String from, final String name) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        if (!name.startsWith("/")) {
            String[] folder = from.split("/");
            for (int i = 0; i < folder.length - 1; i++) parts.addLast(folder[i]);
        }
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalStateException("Bad #include \"" + name + "\" in " + from + ": it leaves the shaders folder");
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private void expand(final String text, final String file, final StringBuilder out, final Set<String> included) {
        expand(text, file, out, included, new java.util.ArrayDeque<>());
    }

    /**
     * {@code stages} holds, for each conditional that is open, the stage it selects ('V' vertex, 'F' fragment, '-' neither): a file is
     * included once per stage, because a program written as one file for both stages (the usual for old packs) includes the same library
     * in its vertex part and in its fragment part, and each stage needs it.
     */
    private void expand(final String text, final String file, final StringBuilder out, final Set<String> included, final java.util.Deque<Character> stages) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                int open = trimmed.indexOf('"');
                int close = trimmed.lastIndexOf('"');
                if (open < 0 || close <= open) throw new IllegalStateException("Bad #include in " + file + ": " + line);
                String name = includePath(file, trimmed.substring(open + 1, close));
                char stage = '-';
                for (char c : stages) if (c != '-') { stage = c; break; }
                if (!included.add(name + "@" + stage)) continue;
                String library = read(name);
                if (library == null) throw new IllegalStateException("Missing shader include " + name + " (from " + file + ")");
                expand(library, name, out, included, stages);
            } else {
                if (trimmed.startsWith("#")) trackStage(trimmed, stages);
                out.append(line).append('\n');
            }
        }
    }

    private static void trackStage(final String directive, final java.util.Deque<Character> stages) {
        String d = directive.replaceAll("^#\\s*", "");
        if (d.startsWith("if")) {
            char stage = d.matches(".*\\b(VERTEX_SHADER|VSH)\\b.*") ? 'V' : d.matches(".*\\b(FRAGMENT_SHADER|FSH)\\b.*") ? 'F' : '-';
            boolean negated = d.startsWith("ifndef") || d.matches("if\\s*!.*");
            if (negated && stage != '-') stage = stage == 'V' ? 'F' : 'V';
            stages.push(stage);
        } else if (d.startsWith("else") && !stages.isEmpty()) {
            char top = stages.pop();
            stages.push(top == 'V' ? 'F' : top == 'F' ? 'V' : '-');
        } else if (d.startsWith("endif") && !stages.isEmpty()) {
            stages.pop();
        }
    }
}
