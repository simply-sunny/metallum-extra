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
 * The programs of the stages in {@link Stage} that {@link Stage#supported()} are run; the pack's other programs
 * (the world's {@code gbuffers_*} and {@code shadow} ones, {@code setup}, compute shaders) are listed in
 * {@link #unsupported()} and left alone.
 */
public final class ProgramSet {
    /** The stages of a frame that run a program over the whole screen, in the order Iris runs them in. */
    public enum Stage {
        BEGIN, SHADOWCOMP, PREPARE, DEFERRED, COMPOSITE, FINAL,
        /** The programs that draw the world's geometry; the game draws with them, they are not run as full-screen passes. */
        GBUFFERS,
        /** The programs that draw the world from the light's point of view into the shadow map. */
        SHADOW;

        /** Whether this version runs the stage's programs; the others are found and reported. */
        public boolean supported() {
            return this != SHADOWCOMP;
        }
    }

    public static final List<String> DIMENSIONS = List.of("world0", "world-1", "world1");

    /** A program's file name: its stage, then for most stages a number from 1 to 99 (none means the first). */
    private static final Pattern NUMBERED = Pattern.compile("(begin|shadowcomp|prepare|deferred|composite)([1-9]\\d?)?");
    /** The gbuffers programs that can draw something this version draws with them: terrain, entities and items, moving blocks, particles, weather, and the programs Iris falls back to. */
    private static final Pattern TERRAIN_PROGRAM = Pattern.compile("gbuffers_(terrain|terrain_solid|terrain_cutout|water|textured_lit|textured|basic"
            + "|entities|entities_translucent|block|block_translucent|particles|particles_translucent|weather|hand|hand_water|spidereyes|clouds|skybasic)|shadow|shadow_solid|shadow_cutout|shadow_entities|shadow_block");
    private static final Pattern FILE = Pattern.compile("(?:(world-?\\d+)/)?([a-z][a-z0-9_]*)\\.(vsh|fsh|gsh|csh|tcs|tes)");
    /** Every other name Iris defines, which this version finds and reports without running. */
    private static final Pattern OTHER = Pattern.compile("setup[1-9]?\\d?|shadow|shadow_[a-z]+|dh_shadow|(?:dh_)?gbuffers_[a-z_]+|(?:begin|shadowcomp|prepare|deferred|composite)[1-9]?\\d?_[a-z]");
    private static final Pattern SAMPLER = Pattern.compile("(?m)^\\s*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2D\\s+(\\w+)\\s*;");

    /** One program: its stage, its number within the stage, and where its files are (a path inside the pack; null where it has none). */
    public record Program(String name, Stage stage, int number, @Nullable String vertex, @Nullable String fragment, boolean geometry) {
        public boolean complete() {
            return vertex != null && fragment != null;
        }
    }

    private final String dimension;
    private final List<Program> programs;
    private final List<String> unsupported;
    private final Properties properties;
    /** What each option of the pack is set to, for the conditions in {@code properties}. */
    private final java.util.Map<String, String> optionValues;
    private final List<PackOption> options;

    private ProgramSet(final String dimension, final List<Program> programs, final List<String> unsupported, final Properties properties,
                       final java.util.Map<String, String> optionValues, final List<PackOption> options) {
        this.dimension = dimension;
        this.programs = programs;
        this.unsupported = unsupported;
        this.properties = properties;
        this.optionValues = optionValues;
        this.options = options;
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
        Set<String> computeOnly = new TreeSet<>();
        List<String> unsupported = new ArrayList<>();
        for (String file : files) {
            Matcher match = FILE.matcher(file);
            if (!match.matches()) continue;
            String folder = match.group(1);
            if (folder != null && !folder.equals(dimension)) continue;
            String name = match.group(2);
            if (TERRAIN_PROGRAM.matcher(name).matches()) {
                if (match.group(3).equals("vsh") || match.group(3).equals("fsh")) names.add(name);
                else if (match.group(3).equals("csh")) computeOnly.add(name);
            } else if (name.equals("final") || NUMBERED.matcher(name).matches()) {
                if (match.group(3).equals("vsh") || match.group(3).equals("fsh")) names.add(name);
                else if (match.group(3).equals("csh")) computeOnly.add(name);
            } else if (OTHER.matcher(name).matches() && !unsupported.contains(name)) {
                unsupported.add(name);
            }
        }
        for (String name : computeOnly) {
            if (!names.contains(name)) unsupported.add(name + " (compute only)");
        }
        List<Program> programs = new ArrayList<>();
        for (String name : names) {
            Stage stage = stageOf(name);
            if (!stage.supported()) {
                unsupported.add(name + " (needs shadow maps)");
                continue;
            }
            programs.add(new Program(name, stage, numberOf(name), find(files, dimension, name, "vsh"), find(files, dimension, name, "fsh"),
                    find(files, dimension, name, "gsh") != null));
        }
        programs.sort(java.util.Comparator.comparing(Program::stage).thenComparingInt(Program::number));
        unsupported.sort(String::compareTo);
        return new ProgramSet(dimension == null ? "" : dimension, List.copyOf(programs), List.copyOf(unsupported), properties(pack),
                StandardOptions.values(pack.name(), pack.options()), pack.options());
    }

    private static Stage stageOf(final String name) {
        if (name.equals("final")) return Stage.FINAL;
        if (name.startsWith("gbuffers_")) return Stage.GBUFFERS;
        if (name.equals("shadow") || name.startsWith("shadow_")) return Stage.SHADOW;
        Matcher match = NUMBERED.matcher(name);
        match.matches();
        return Stage.valueOf(match.group(1).toUpperCase(java.util.Locale.ROOT));
    }

    private static int numberOf(final String name) {
        Matcher match = NUMBERED.matcher(name);
        return match.matches() && match.group(2) != null ? Integer.parseInt(match.group(2)) : 0;
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

    /** The options of the pack, which the conditions in its files may be on. */
    public List<PackOption> options() {
        return options;
    }

    /** A value of {@code shaders.properties}, or null. */
    public @Nullable String property(final String key) {
        String value = properties.getProperty(key);
        return value == null ? null : value.strip();
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
     * {@code program.<name>.enabled=<condition>} in {@code shaders.properties} switches a program off when the condition is false: {@code true},
     * {@code false}, or a test of the pack's options (see {@link OptionExpression}). A condition that cannot be read leaves the program on.
     */
    private boolean enabled(final String name) {
        String value = properties.getProperty("program." + (dimension.isEmpty() ? "" : dimension + "/") + name + ".enabled");
        if (value == null) value = properties.getProperty("program." + name + ".enabled");
        if (value == null) return true;
        try {
            return OptionExpression.evaluate(value.strip(), optionValues);
        } catch (PackException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] program {} is left on: {}", name, e.getMessage());
            return true;
        }
    }

    /** The names of the {@code sampler2D} uniforms a shader declares. */
    public static List<String> samplers(final String source) {
        List<String> result = new ArrayList<>();
        Matcher match = SAMPLER.matcher(source);
        while (match.find()) result.add(match.group(1));
        return result;
    }

    /** The programs of a stage that can run, in the order they run in. */
    public List<Program> runnable(final Stage stage) {
        List<Program> result = new ArrayList<>();
        for (Program program : runnable()) {
            if (program.stage == stage) result.add(program);
        }
        return result;
    }

    /** Programs with a geometry shader, which this version cannot run. */
    public List<String> withGeometry() {
        List<String> result = new ArrayList<>();
        for (Program program : runnable()) {
            if (program.geometry) result.add(program.name);
        }
        return result;
    }

    /**
     * The buffers whose flip after {@code program} is switched off, from {@code flip.<program>.<buffer>=false} in
     * {@code shaders.properties}. Buffers are named {@code colortexN}.
     */
    public Set<Integer> flipsOff(final String program) {
        Set<Integer> result = new TreeSet<>();
        String prefix = "flip." + program + ".colortex";
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix) || !properties.getProperty(key).strip().equalsIgnoreCase("false")) continue;
            try {
                result.add(Integer.parseInt(key.substring(prefix.length())));
            } catch (NumberFormatException e) {
                // not a buffer name
            }
        }
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
            for (String name : set.withGeometry()) {
                throw new PackException(name + " has a geometry shader, which this version cannot run yet");
            }
            if (!set.runnable().isEmpty()) {
                any = true;
                IrisPlan.build(pack, dimension);
            }
            for (String name : set.unsupported()) {
                if (!others.contains(name)) others.add(name);
            }
        }
        if (!any) {
            throw new PackException("None of its programs can run in this version"
                    + (others.isEmpty() ? " (it has none)" : " (it has " + String.join(", ", others.subList(0, Math.min(5, others.size()))) + (others.size() > 5 ? " and more" : "")
                    + "; only begin, prepare, deferred, composite and final run so far)"));
        }
    }
}
