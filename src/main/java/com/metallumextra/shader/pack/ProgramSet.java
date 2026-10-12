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
        /** Compute programs that run once, when the pack starts. */
        SETUP,
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
            + "|entities|entities_translucent|block|block_translucent|particles|particles_translucent|weather|hand|hand_water|spidereyes|clouds|skybasic|skytextured|armor_glint|beaconbeam|lightning)|shadow|shadow_solid|shadow_cutout|shadow_entities|shadow_block");
    /** A compute program: a stage (and the number of one of its programs), then optionally the letter that puts it after that program. */
    private static final Pattern COMPUTE_NAME = Pattern.compile("(setup|begin|shadowcomp|prepare|deferred|composite)([1-9]\\d?)?(?:_([a-z]))?");
    private static final Pattern FILE = Pattern.compile("(?:(world-?\\d+)/)?([a-z][a-z0-9_]*)\\.(vsh|fsh|gsh|csh|tcs|tes)");
    /** Every other name Iris defines, which this version finds and reports without running. */
    private static final Pattern OTHER = Pattern.compile("setup[1-9]?\\d?|shadow|shadow_[a-z]+|dh_shadow|(?:dh_)?gbuffers_[a-z_]+|(?:begin|shadowcomp|prepare|deferred|composite)[1-9]?\\d?_[a-z]");
    private static final Pattern SAMPLER = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?([ui]?sampler[123]D)\\s+(\\w+)\\s*;");

    /** One program: its stage, its number within the stage, and where its files are (a path inside the pack; null where it has none). */
    public record Program(String name, Stage stage, int number, @Nullable String vertex, @Nullable String fragment, boolean geometry,
                          @Nullable String compute, char letter) {
        public Program(final String name, final Stage stage, final int number, final @Nullable String vertex, final @Nullable String fragment, final boolean geometry) {
            this(name, stage, number, vertex, fragment, geometry, null, '\0');
        }

        /** A compute program: no vertex and fragment files, and a letter after its stage's own name ({@code deferred4_a}), or none. */
        public boolean isCompute() {
            return compute != null;
        }

        public boolean complete() {
            return compute != null || vertex != null && fragment != null;
        }
    }

    private final String dimension;
    private final List<Program> programs;
    private final List<String> unsupported;
    private final Properties properties;
    /** The properties in file order, which custom uniforms need (a variable may use an earlier one). */
    private java.util.Map<String, String> ordered = java.util.Map.of();
    /** What each option of the pack is set to, for the conditions in {@code properties}. */
    private final java.util.Map<String, String> optionValues;
    private final List<PackOption> options;
    private @Nullable ShaderPack pack;

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
            } else if (match.group(3).equals("csh") && COMPUTE_NAME.matcher(name).matches()) {
                computeOnly.add(name);
            } else if (OTHER.matcher(name).matches() && !unsupported.contains(name)) {
                unsupported.add(name);
            }
        }
        List<Program> programs = new ArrayList<>();
        for (String name : computeOnly) {
            Matcher compute = COMPUTE_NAME.matcher(name);
            String path = find(files, dimension, name, "csh");
            if (!compute.matches() || path == null) {
                if (!names.contains(name)) unsupported.add(name + " (compute only)");
                continue;
            }
            Stage stage = Stage.valueOf(compute.group(1).toUpperCase(java.util.Locale.ROOT));
            int number = compute.group(2) == null ? 0 : Integer.parseInt(compute.group(2));
            programs.add(new Program(name, stage, number, null, null, false, path, compute.group(3) == null ? '\0' : compute.group(3).charAt(0)));
        }
        for (String name : names) {
            Stage stage = stageOf(name);
            if (!stage.supported()) {
                unsupported.add(name + " (needs shadow maps)");
                continue;
            }
            String vertex = find(files, dimension, name, "vsh");
            String fragment = find(files, dimension, name, "fsh");
            // Without a vertex shader a full-screen program gets the default one, as in Iris; a program without a fragment shader draws nothing and is left out.
            if (vertex == null && fragment != null && stage != Stage.GBUFFERS && stage != Stage.SHADOW) vertex = ShaderPack.DEFAULT_VERTEX;
            programs.add(new Program(name, stage, numberOf(name), vertex, fragment, find(files, dimension, name, "gsh") != null));
        }
        programs.sort(java.util.Comparator.comparing(Program::stage).thenComparingInt(Program::number).thenComparingInt(Program::letter));
        unsupported.sort(String::compareTo);
        ProgramSet result = new ProgramSet(dimension == null ? "" : dimension, List.copyOf(programs), List.copyOf(unsupported), properties(pack),
                StandardOptions.values(pack.name(), pack.options()), pack.options());
        result.pack = pack;
        String text = pack.read("shaders.properties");
        if (text != null) result.ordered = PropertiesFile.parse(text, PropertiesFile.macros(result.optionValues));
        return result;
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
            result.putAll(PropertiesFile.parse(text, PropertiesFile.macros(StandardOptions.values(pack.name(), pack.options()))));
        } catch (RuntimeException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Shader pack {}: shaders.properties could not be read: {}", pack.name(), e.getMessage());
        }
        return result;
    }

    /** {@code shaders.properties} as the preprocessor left it, in file order. */
    public java.util.Map<String, String> orderedProperties() {
        return ordered;
    }

    /** The block ids the pack's {@code block.properties} gives; none when it has no such file. */
    public BlockMappings blockMappings() {
        String text = pack().read("block.properties");
        if (text == null) return BlockMappings.none();
        return BlockMappings.parse(PropertiesFile.parse(text, PropertiesFile.macros(optionValues)));
    }

    /** The ids the pack's {@code item.properties} gives items ({@code heldItemId}); none when it has no such file. */
    public BlockMappings itemMappings() {
        return mappings("item.properties", "item.");
    }

    /** The ids the pack's {@code entity.properties} gives entities ({@code entityId}); none when it has no such file. */
    public BlockMappings entityMappings() {
        return mappings("entity.properties", "entity.");
    }

    private BlockMappings mappings(final String file, final String prefix) {
        String text = pack().read(file);
        if (text == null) return BlockMappings.none();
        return BlockMappings.parse(PropertiesFile.parse(text, PropertiesFile.macros(optionValues)), prefix);
    }

    /** The custom uniforms and variables the pack defines, ready to be evaluated; the constants (biomes) are supplied by the game. */
    public CustomUniforms customUniforms(final java.util.function.Function<String, @Nullable Double> constant) throws PackException {
        return CustomUniforms.parse(ordered, constant);
    }

    /** The pack these programs are from. */
    public ShaderPack pack() {
        return java.util.Objects.requireNonNull(pack);
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

    /** The path a program reads a custom image by (see {@link #customImages}): {@code mx:image:<name>}, which the pipeline resolves to the image itself. */
    public static final String IMAGE_PATH = "mx:image:";

    /**
     * A picture a compute program reads or writes by name, declared as {@code image.<name> = <sampler> <channels> <internal format> <type> <clear> <relative>
     * <size ...>} (one to three sizes; as fractions of the screen when relative). {@code sampler} is the name a program reads it by as a texture, if any.
     */
    public record CustomImage(String name, String sampler, String internal, boolean clear, boolean relative, float[] sizes) {
    }

    /** The custom images the pack declares, by name, in the order of shaders.properties; one with a malformed declaration is left out. */
    public List<CustomImage> customImages() {
        List<CustomImage> result = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(properties.stringPropertyNames())) {
            if (!key.startsWith("image.")) continue;
            String[] parts = property(key).split("\\s+");
            if (parts.length < 7 || parts.length > 9) continue;
            try {
                float[] sizes = new float[parts.length - 6];
                for (int i = 0; i < sizes.length; i++) sizes[i] = Float.parseFloat(parts[6 + i]);
                result.add(new CustomImage(key.substring("image.".length()), parts[0], parts[2], parts[4].equals("true"), parts[5].equals("true"), sizes));
            } catch (NumberFormatException e) {
                // Not a size: the declaration is ignored, as a missing one would be.
            }
        }
        return List.copyOf(result);
    }

    /** The custom image a pack declares for a sampler, which the program reads as {@link #IMAGE_PATH}{@code <name>}; null when none does. */
    private @Nullable String imageFor(final String sampler) {
        for (CustomImage image : customImages()) if (image.sampler().equals(sampler)) return IMAGE_PATH + image.name();
        return null;
    }

    /**
     * The picture a pack gives a sampler in a stage: {@code texture.<stage>.<sampler>=<path>} in {@code shaders.properties}, or {@code texture.noise} for
     * {@code noisetex} everywhere. The stage is begin, prepare, shadow, deferred, composite (which includes final) or gbuffers. Null when there is none.
     */
    public @Nullable String customTexture(final Stage stage, final String sampler) {
        String stageName = switch (stage) {
            case SETUP -> "setup";
            case BEGIN -> "begin";
            case SHADOWCOMP -> "shadowcomp";
            case PREPARE -> "prepare";
            case DEFERRED -> "deferred";
            case COMPOSITE, FINAL -> "composite";
            case GBUFFERS -> "gbuffers";
            case SHADOW -> "shadow";
        };
        String value = property("texture." + stageName + "." + sampler);
        if (value == null) {
            // OptiFine's texture.<stage>.<name>.<unit>: the unit says nothing here, the name is what the program reads.
            String prefix = "texture." + stageName + "." + sampler + ".";
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(prefix) && key.substring(prefix.length()).matches("\\d+")) {
                    value = property(key);
                    break;
                }
            }
        }
        if (value == null && sampler.equals("noisetex")) value = property("texture.noise");
        // customTexture.<sampler> gives a sampler a picture in every stage.
        if (value == null) value = property("customTexture." + sampler);
        if (value == null) value = imageFor(sampler);
        if (value == null || value.isBlank()) return null;
        String path = TextureSpec.pathOf(value);
        // A picture, or a file of raw numbers that says how to read it.
        return value.startsWith(IMAGE_PATH) || path.endsWith(".png") || TextureSpec.parse(value) != null ? value.trim() : null;
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

    private static final Pattern SAMPLER_DECLARATION = Pattern.compile("(?m)^[ \\t]*(?:writeonly[ \\t]+|readonly[ \\t]+)?uniform[ \\t]+(?:(?:highp|mediump|lowp)[ \\t]+)?\\w+[ \\t]+(\\w+)[ \\t]*;[ \\t]*(?://.*)?$");

    /**
     * The source without the declarations of uniforms (samplers too) that nothing reads. A pack that shares one list of uniforms between all its programs
     * declares every one of them in every program, and a program is only given what it reads.
     */
    public static String withoutUnusedUniforms(final String source) {
        String code = LegacyGlsl.code(source);
        Matcher declarations = SAMPLER_DECLARATION.matcher(code);
        if (!declarations.find()) return source;
        // How many times each word occurs, counted once for the whole program.
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        Matcher words = WORD.matcher(code);
        while (words.find()) counts.merge(words.group(), 1, Integer::sum);
        java.util.List<int[]> remove = new ArrayList<>();
        declarations.reset();
        while (declarations.find()) {
            if (counts.getOrDefault(declarations.group(1), 0) == 1) remove.add(new int[] {declarations.start(), declarations.end()});
        }
        if (remove.isEmpty()) return source;
        StringBuilder out = new StringBuilder(source);
        for (int i = remove.size() - 1; i >= 0; i--) out.replace(remove.get(i)[0], remove.get(i)[1], "");
        return out.toString();
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z_]\\w*");

    /** The names of the {@code sampler2D} uniforms a shader declares. */
    public static List<String> samplers(final String source) {
        return new ArrayList<>(samplerTypes(source).keySet());
    }

    /** The samplers a shader declares, with their types ({@code sampler2D}, {@code sampler3D}, ...), in the order they are declared. */
    public static java.util.Map<String, String> samplerTypes(final String source) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        Matcher match = SAMPLER.matcher(source);
        while (match.find()) result.putIfAbsent(match.group(2), match.group(1));
        return result;
    }

    /** Whether a texture the pack names can be read as a sampler of this type: a raw 3D texture only as a {@code sampler3D}, a picture only as a 2D one. */
    public static boolean fits(final String texture, final String samplerType) {
        TextureSpec spec = TextureSpec.parse(texture);
        String dimension = spec == null ? "2D" : spec.target().substring("TEXTURE_".length());
        return samplerType.endsWith("sampler" + dimension);
    }

    /** The programs of a stage that can run, in the order they run in. */
    public List<Program> runnable(final Stage stage) {
        List<Program> result = new ArrayList<>();
        for (Program program : runnable()) {
            if (program.stage == stage) result.add(program);
        }
        return result;
    }

    /** Programs with a geometry shader. Their geometry stage is not run. */
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
        check(pack, false);
    }

    /**
     * {@link #validate} and then a plan of every dimension, which reads and checks every program: seconds for a big pack, so it is done when the
     * pack is put into use. It leaves the layout of the standard uniforms' block as it found it.
     */
    public static void validateFully(final ShaderPack pack) throws PackException {
        Object saved = IrisUniforms.saveCustom();
        try {
            check(pack, true);
        } finally {
            IrisUniforms.restoreCustom(saved);
        }
    }

    private static void check(final ShaderPack pack, final boolean plan) throws PackException {
        boolean any = false;
        List<String> others = new ArrayList<>();
        for (String dimension : DIMENSIONS) {
            ProgramSet set = discover(pack, dimension);
            for (String name : set.withGeometry()) {
                MetallumExtra.LOGGER.warn("[Metallum Extra] {} has a geometry shader, which is not run: the program runs without it", name);
            }
            if (!set.runnable().isEmpty()) {
                any = true;
                if (plan) IrisPlan.build(pack, dimension);
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
