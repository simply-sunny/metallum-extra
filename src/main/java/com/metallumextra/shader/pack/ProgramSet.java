package com.metallumextra.shader.pack;

import com.metallumextra.MetallumExtra;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The programs of a standard (Iris layout) pack for one dimension.
 * <p>
 * A program is a pair of files named after it: {@code final.vsh} and {@code final.fsh}. A file in the dimension's folder
 * ({@code world0/}, {@code world-1/}, {@code world1/}) is used in that dimension, and one in the {@code shaders/} folder
 * itself in every dimension that has none of its own. Programs the pack leaves out are simply not run.
 * <p>
 * Only the programs listed in {@link #SUPPORTED} are run so far; the pack's other programs are listed in
 * {@link #unsupported()} and left alone.
 */
public final class ProgramSet {
    /** The programs this version can run. */
    public static final List<String> SUPPORTED = List.of("final");

    public static final List<String> DIMENSIONS = List.of("world0", "world-1", "world1");

    /** The name of every program Iris defines, without its dimension folder or file extension (numbers and variants included). */
    private static final Pattern FILE = Pattern.compile("(?:(world-?\\d+)/)?([a-z][a-z0-9_]*)\\.(vsh|fsh|gsh|tcs|tes|csh)");
    private static final Pattern PROGRAM = Pattern.compile("setup\\d*|begin\\d*|prepare\\d*|deferred\\d*|composite\\d*|shadowcomp\\d*|shadow|final"
            + "|(?:dh_)?gbuffers_[a-z_]+|dh_shadow|shadow_[a-z]+");
    private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2D\\s+(\\w+)\\s*;");

    /** The samplers a program may declare so far; see {@link #samplers}. */
    public static final Set<String> SUPPORTED_SAMPLERS = Set.of("colortex0", "depthtex0");

    /** One program: where its two files are (a path inside the pack), null where the pack has none. */
    public record Program(String name, @Nullable String vertex, @Nullable String fragment) {
        public boolean complete() {
            return vertex != null && fragment != null;
        }
    }

    private final String dimension;
    private final List<Program> programs;
    private final List<String> unsupported;
    private final Properties properties;

    private ProgramSet(final String dimension, final List<Program> programs, final List<String> unsupported, final Properties properties) {
        this.dimension = dimension;
        this.programs = programs;
        this.unsupported = unsupported;
        this.properties = properties;
    }

    /** The folder for a dimension, or null for one that has none (it then uses the shared files only). */
    public static @Nullable String folderOf(final String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> "world0";
            case "minecraft:the_nether" -> "world-1";
            case "minecraft:the_end" -> "world1";
            default -> null;
        };
    }

    /** @param dimension a folder name from {@link #DIMENSIONS}, or null for the shared files only */
    public static ProgramSet discover(final ShaderPack pack, final @Nullable String dimension) {
        Set<String> files = pack.files();
        Set<String> names = new TreeSet<>();
        List<String> unsupported = new ArrayList<>();
        for (String file : files) {
            Matcher match = FILE.matcher(file);
            if (!match.matches() || !PROGRAM.matcher(match.group(2)).matches()) continue;
            String folder = match.group(1);
            if (folder != null && !folder.equals(dimension)) continue;
            names.add(match.group(2));
        }
        List<Program> programs = new ArrayList<>();
        for (String name : names) {
            if (!SUPPORTED.contains(name)) {
                unsupported.add(name);
                continue;
            }
            programs.add(new Program(name, find(files, dimension, name, "vsh"), find(files, dimension, name, "fsh")));
        }
        return new ProgramSet(dimension == null ? "" : dimension, List.copyOf(programs), List.copyOf(unsupported), properties(pack));
    }

    private static @Nullable String find(final Set<String> files, final @Nullable String dimension, final String name, final String extension) {
        if (dimension != null && files.contains(dimension + "/" + name + "." + extension)) return dimension + "/" + name + "." + extension;
        return files.contains(name + "." + extension) ? name + "." + extension : null;
    }

    private static Properties properties(final ShaderPack pack) {
        Properties result = new Properties();
        String text = pack.read("shaders.properties");
        if (text == null) return result;
        try {
            result.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Shader pack {}: shaders.properties could not be read: {}", pack.name(), e.getMessage());
        }
        return result;
    }

    /** The programs of the pack that can be run here, in the order they run in, with their files present and not switched off. */
    public List<Program> runnable() {
        List<Program> result = new ArrayList<>();
        for (Program program : programs) {
            if (program.complete() && enabled(program.name)) result.add(program);
        }
        return result;
    }

    public @Nullable Program find(final String name) {
        for (Program program : runnable()) {
            if (program.name.equals(name)) return program;
        }
        return null;
    }

    /** Programs found in the pack that this version does not run yet. */
    public List<String> unsupported() {
        return unsupported;
    }

    /** Programs with only one of their two files, which cannot be run. */
    public List<String> incomplete() {
        List<String> result = new ArrayList<>();
        for (Program program : programs) {
            if (!program.complete()) result.add(program.name);
        }
        return result;
    }

    /**
     * {@code program.<name>.enabled=false} in {@code shaders.properties} switches a program off. Only the plain values
     * {@code true} and {@code false} are understood so far; anything else leaves the program on.
     */
    private boolean enabled(final String name) {
        String value = properties.getProperty("program." + (dimension.isEmpty() ? "" : dimension + "/") + name + ".enabled");
        if (value == null) value = properties.getProperty("program." + name + ".enabled");
        return value == null || !value.strip().equalsIgnoreCase("false");
    }

    /** The names of the {@code sampler2D} uniforms a shader declares. */
    public static List<String> samplers(final String source) {
        List<String> result = new ArrayList<>();
        Matcher match = SAMPLER.matcher(source);
        while (match.find()) result.add(match.group(1));
        return result;
    }

    /**
     * Checks that a standard pack has something this version can run, and that what it has can be.
     *
     * @throws PackException saying what is wrong
     */
    public static void validate(final ShaderPack pack) throws PackException {
        boolean any = false;
        List<String> others = new ArrayList<>();
        for (String dimension : DIMENSIONS) {
            ProgramSet set = discover(pack, dimension);
            for (String name : set.incomplete()) {
                throw new PackException(name + " needs both " + name + ".vsh and " + name + ".fsh");
            }
            for (Program program : set.runnable()) {
                any = true;
                try {
                    String vertex = pack.load(program.vertex);
                    String fragment = pack.load(program.fragment);
                    for (String sampler : samplers(vertex + "\n" + fragment)) {
                        if (!SUPPORTED_SAMPLERS.contains(sampler)) {
                            throw new PackException(program.name + " reads " + sampler + ", which this version cannot provide yet");
                        }
                    }
                } catch (IllegalStateException e) {
                    throw new PackException(e.getMessage(), e);
                }
            }
            for (String name : set.unsupported()) {
                if (!others.contains(name)) others.add(name);
            }
        }
        if (!any) {
            throw new PackException("None of its programs can run in this version"
                    + (others.isEmpty() ? " (it has none)" : " (it has " + String.join(", ", others.subList(0, Math.min(5, others.size()))) + (others.size() > 5 ? " and more" : "") + "; only " + String.join(", ", SUPPORTED) + " runs so far)"));
        }
    }
}
