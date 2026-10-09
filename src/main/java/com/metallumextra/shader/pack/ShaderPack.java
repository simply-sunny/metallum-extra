package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A complete set of shader files in the Iris layout. Paths are relative to the pack's {@code shaders/} folder:
 * {@code world0/final.fsh}, {@code shaders.properties}, {@code lib/lighting.glsl}.
 * <p>
 * A pack never falls back to another one: what it does not contain is missing.
 */
public interface ShaderPack {
    /** The file's text, or null if the pack has no such file. */
    @Nullable String read(String path);

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
     * of the files it includes ask for it. The pack's options, as the player set them, are written into the text.
     *
     * @throws IllegalStateException if the file or something it includes is not in the pack
     */
    default String load(final String path) {
        String text = read(path);
        if (text == null) throw new IllegalStateException("Shader pack " + name() + " has no " + path);
        StringBuilder out = new StringBuilder(text.length() + 4096);
        expand(text, path, out, new HashSet<>());
        List<PackOption> options = options();
        return options.isEmpty() ? out.toString() : StandardOptions.apply(out.toString(), name(), options);
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
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                int open = trimmed.indexOf('"');
                int close = trimmed.lastIndexOf('"');
                if (open < 0 || close <= open) throw new IllegalStateException("Bad #include in " + file + ": " + line);
                String name = includePath(file, trimmed.substring(open + 1, close));
                if (!included.add(name)) continue;
                String library = read(name);
                if (library == null) throw new IllegalStateException("Missing shader include " + name + " (from " + file + ")");
                expand(library, name, out, included);
            } else {
                out.append(line).append('\n');
            }
        }
    }
}
