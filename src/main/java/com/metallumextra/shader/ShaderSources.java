package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.BuiltinPack;
import com.metallumextra.shader.pack.InternalShaders;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GLSL of the shader pack in use, looked up by the same id the game or Sodium asks its own shaders by. The pack
 * is the built-in one (the jar) or a ZIP in the shaderpacks folder; see {@link PackManager}. Nothing is ever taken
 * from any pack but the one in use.
 * <p>
 * Which of the pack's programs is asked for is worked out from the id: Sodium's terrain shader (in a pipeline Sodium builds with a made-up id,
 * see {@link IrisPipeline#terrainShaderId}), the game's own pipelines for mobs, items, particles and clouds (see {@link IrisWorld}), this mod's own
 * passes ({@code metallum-extra:standard/<generation>/<program>}) and the shadow map's. Each program is fitted to the pipeline it is for.
 * A line {@code #include "name.glsl"} is replaced by the file it names, as in Iris. Every file must start with its {@code #version} line, since the
 * game inserts a pipeline's defines right after the first line.
 */
public final class ShaderSources {
    /** Development aid: every shader the game compiles (as other mods left it) is written to this folder. */
    private static final @Nullable Path DUMP_DIR = dir("metallumextra.dumpShaders");

    /** What has been read from one pack. A new one replaces it whenever the pack changes, so a stale entry cannot outlive its pack. */
    private record Lookup(ShaderPack pack, Map<String, Optional<String>> files, Map<String, String> numbered) {
    }

    private static final Map<String, Optional<String>> INTERNAL = new ConcurrentHashMap<>();

    private static volatile Lookup lookup = new Lookup(new BuiltinPack(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());

    private ShaderSources() {
    }

    /** Wraps the source the game compiles from, so this mod's shaders are found first while shaders are on. */
    public static ShaderSource wrap(final ShaderSource original) {
        return new ShaderSource() {
            @Override
            public ShaderSource.CachedIncludeSource getInclude(final Identifier id) {
                return original.getInclude(id);
            }

            @Override
            public void close() {
                original.close();
            }

            @Override
            public String getShader(final Identifier id, final ShaderType type) {
                return shaderFor(original, id, type);
            }
        };
    }

    private static String shaderFor(final ShaderSource original, final Identifier id, final ShaderType type) {
        {
            String ours = null;
            if (Shaders.active()) {
                try {
                    try (var step = com.metallumextra.ResourceLog.step("adapt-source", id + " " + type)) {
                        ours = withoutPipelineDefines(get(id, type));
                    }
                } catch (RuntimeException e) {
                    // A pack's program that cannot be made is never a crash: the pack is put aside and the game goes on without it.
                    ours = null;
                    if (!isStale(e)) PackManager.fail(e);
                    return fallback(original, id, type);
                }
            }
            Identifier world = IrisWorld.originalOf(id, type == ShaderType.VERTEX);
            Identifier real = isTerrainProgram(id) ? Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque") : world != null ? world : id;
            if (DUMP_DIR != null) dump(id, type, ours != null ? ours : original.getShader(real, type));
            // A terrain pipeline made for a pack that has since gone (Sodium keeps them) compiles as Sodium's own shader until it is replaced.
            return ours != null ? ours : original.getShader(real, type);
        }
    }

    /** The flags the game's pipelines define in their shaders; a pack that defines one of the names itself means something else by it. */
    private static final java.util.List<String> PIPELINE_DEFINES = java.util.List.of("ALPHA_CUTOUT", "APPLY_TEXTURE_MATRIX", "DISSOLVE", "EMISSIVE", "IS_GRAYSCALE",
            "IS_GUI", "IS_SEE_THROUGH", "NO_CARDINAL_LIGHTING", "NO_OVERLAY", "PER_FACE_LIGHTING");

    /**
     * The game inserts its pipeline's defines right after the first line, so a pack that defines the same name again (BSL has its own {@code EMISSIVE})
     * is told off by the compiler for a macro defined twice, and would also see the game's flag as its own. The pack's own definition wins: the
     * name is undefined on the second line, after the game's.
     */
    static @Nullable String withoutPipelineDefines(final @Nullable String source) {
        if (source == null) return null;
        StringBuilder undef = new StringBuilder();
        for (String name : PIPELINE_DEFINES) {
            if (Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+" + name + "\\b").matcher(source).find()) undef.append("#undef ").append(name).append('\n');
        }
        if (undef.length() == 0) return source;
        int firstLine = source.indexOf('\n');
        return firstLine < 0 ? source : source.substring(0, firstLine + 1) + undef + source.substring(firstLine + 1);
    }

    private static boolean isStale(final Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof StalePipelineException) return true;
        }
        return false;
    }

    /**
     * What a pipeline compiles from when its pack's program could not be made: the game's own program where there is one, else a program that
     * draws nothing. The pipelines of the pack are thrown away at the start of the next frame, so this only has to compile.
     */
    private static String fallback(final ShaderSource original, final Identifier id, final ShaderType type) {
        Identifier world = IrisWorld.originalOf(id, type == ShaderType.VERTEX);
        Identifier real = isTerrainProgram(id) ? Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque") : world != null ? world : id;
        try {
            String source = original.getShader(real, type);
            if (source != null) return source;
        } catch (RuntimeException ignored) {
            // No program of the game's by that name: the empty one below.
        }
        return type == ShaderType.VERTEX
                ? "#version 330\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);\n}\n"
                : "#version 330\nlayout(location = 0) out vec4 mx_color;\nvoid main() {\n    mx_color = vec4(0.0);\n}\n";
    }

    /** Whether the id is one of {@link IrisPipeline#terrainShaderId}'s: Sodium's terrain shader replaced by a standard pack's program. */
    private static boolean isTerrainProgram(final Identifier id) {
        return id.getNamespace().equals("sodium") && id.getPath().startsWith("blocks/iris_");
    }

    /**
     * A standard pack's program for a layer of terrain, fitted to Sodium's pipeline; null if the id belongs to a plan that is no longer
     * current. The id is {@code blocks/iris_<generation>_<layer>_<program>}.
     */
    private static @Nullable String terrain(final Lookup current, final Identifier id, final ShaderType type) {
        String[] parts = id.getPath().substring("blocks/iris_".length()).split("_", 3);
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null || parts.length != 3 || Integer.parseInt(parts[0]) != IrisPipeline.generation()) return null;
        IrisPlan.Layer layer = IrisPlan.Layer.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
        IrisPlan.Step step = plan.step(parts[2]);
        ProgramSet.Program program = step == null ? null : step.program();
        String file = program == null ? null : type == ShaderType.VERTEX ? program.vertex() : program.fragment();
        if (file == null) return null;
        // The fragment stage is alpha-tested by layer, so the same file may be fitted twice.
        String key = type == ShaderType.VERTEX ? file : file + "#" + layer;
        return current.files.computeIfAbsent(key, k -> {
            String f = file;
            String text;
            try (var timer = com.metallumextra.ResourceLog.step("read-program", f)) {
                text = readExpanded(current.pack, f);
            }
            try (var timer = com.metallumextra.ResourceLog.step("adapt-program", f)) {
                if (text == null) return Optional.empty();
                String clean;
                try (var t = com.metallumextra.ResourceLog.step("ad-buffer-formats", f)) {
                    clean = IrisPlan.withoutBufferFormats(text);
                }
                String adapted;
                try (var t = com.metallumextra.ResourceLog.step("ad-terrain-adapt", f)) {
                    adapted = IrisTerrain.adapt(clean, type == ShaderType.VERTEX, false, "true".equals(plan.programs().property("mx.blockTypes")), plan.usesBlockIds());
                }
                if (type != ShaderType.VERTEX) adapted = IrisWorldAdapter.remapOutputs(adapted, step.writes(), plan, plan.terrainAlphaThreshold(program.name(), layer));
                java.util.List<String> declared;
                try (var t = com.metallumextra.ResourceLog.step("ad-declared", f)) {
                    declared = IrisUniforms.declared(adapted);
                }
                try (var t = com.metallumextra.ResourceLog.step("ad-rewrite", f)) {
                    return Optional.of(IrisUniforms.rewrite(adapted, declared));
                }
            } catch (PackException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).orElseThrow();
    }

    /** A standard pack's program for one of the game's pipelines (see {@link IrisWorld}), fitted to that pipeline; null if the id is out of date. */
    private static @Nullable String world(final Lookup current, final Identifier id, final ShaderType type) {
        IrisWorld.Entry entry = IrisWorld.entry(id);
        if (entry == null || entry.generation() != IrisPipeline.generation()) return null;
        ProgramSet.Program program = entry.step().program();
        String file = type == ShaderType.VERTEX ? program.vertex() : program.fragment();
        if (file == null) return null;
        return current.files.computeIfAbsent(id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"), f -> {
            String text;
            try (var timer = com.metallumextra.ResourceLog.step("read-program", file)) {
                text = readExpanded(current.pack, file);
            }
            try (var timer = com.metallumextra.ResourceLog.step("adapt-program", file)) {
                if (text == null) return Optional.empty();
                String source = IrisWorldAdapter.withRenderStage(IrisPlan.withoutBufferFormats(text), entry.stage());
                String adapted = entry.kind() == IrisWorld.Kind.SKY ? IrisWorldAdapter.adaptSky(source, type == ShaderType.VERTEX)
                        : IrisWorldAdapter.adapt(source, type == ShaderType.VERTEX, entry.format(), entry.kind() == IrisWorld.Kind.SHADOW);
                if (type != ShaderType.VERTEX) {
                    IrisPlan plan = IrisPipeline.currentPlan();
                    boolean shadowOutputs = entry.kind() == IrisWorld.Kind.SHADOW;
                    adapted = IrisWorldAdapter.remapOutputs(adapted, entry.step().writes(), shadowOutputs ? null : plan, plan == null ? 0.1F : plan.alphaThreshold(program.name()));
                }
                return Optional.of(IrisUniforms.rewrite(adapted, IrisUniforms.declared(adapted)));
            } catch (PackException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).orElseThrow();
    }

    /**
     * A standard pack's shadow program for a layer of terrain, fitted to the pipeline {@link com.metallumextra.shader.sodium.ShadowTerrain} draws it with;
     * the id is {@code shadow_iris_<generation>_<solid|cutout>}. Out of date ids are refused like those of {@link #standard}.
     */
    private static String shadowTerrain(final Lookup current, final String path, final ShaderType type) {
        String[] parts = path.substring("shadow_iris_".length()).split("_", 2);
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null || parts.length != 2 || Integer.parseInt(parts[0]) != IrisPipeline.generation()) {
            throw new StalePipelineException("The shadow shader " + path + " belongs to a pack that is no longer in use");
        }
        IrisPlan.Step step = plan.shadowTerrainStep(parts[1].equals("cutout") ? IrisPlan.Layer.CUTOUT : IrisPlan.Layer.SOLID);
        ProgramSet.Program program = step == null ? null : step.program();
        String file = program == null ? null : type == ShaderType.VERTEX ? program.vertex() : program.fragment();
        if (file == null) throw new IllegalStateException("Shader pack " + current.pack.name() + " has no shadow program for " + parts[1] + " terrain");
        return current.files.computeIfAbsent(path + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"), f -> {
            String text = readExpanded(current.pack, file);
            try {
                if (text == null) return Optional.empty();
                String adapted = IrisTerrain.adapt(IrisPlan.withoutBufferFormats(text), type == ShaderType.VERTEX, true, false, plan.usesBlockIds());
                return Optional.of(IrisUniforms.rewrite(adapted, IrisUniforms.declared(adapted)));
            } catch (PackException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).orElseThrow();
    }

    public static @Nullable String get(final Identifier id, final ShaderType type) {
        PackManager.start();
        Lookup current = lookup;
        String text = unnumbered(current, id, type);
        // The mod's own helper programs are written with their numbers; a program of a pack gets them from its two stages together.
        if (text == null || id.getPath().startsWith("internal/")) return text;
        return current.numbered.computeIfAbsent(id + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"), key -> {
            String other = null;
            try {
                other = unnumbered(current, id, type == ShaderType.VERTEX ? ShaderType.FRAGMENT : ShaderType.VERTEX);
            } catch (RuntimeException e) {
                // The other stage is missing or stale: this one is numbered on its own, and the compiler says what is wrong.
            }
            try (var step = com.metallumextra.ResourceLog.step("number-locations", id + " " + type)) {
                return GlslLocations.fit(text, other, type == ShaderType.VERTEX);
            }
        });
    }

    private static @Nullable String unnumbered(final Lookup current, final Identifier id, final ShaderType type) {
        if (isTerrainProgram(id)) return terrain(current, id, type);
        if (id.getNamespace().equals("minecraft") && id.getPath().startsWith("iris_")) return world(current, id, type);
        String extension = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
        boolean ours = id.getNamespace().equals(MetallumExtra.MOD_ID);
        if (ours && id.getPath().startsWith("internal/")) {
            // The mod's own helper programs, which no pack provides or replaces.
            return INTERNAL.computeIfAbsent(id.getPath() + extension, f -> Optional.ofNullable(InternalShaders.read(f.substring("internal/".length())))).orElseThrow(
                    () -> new IllegalStateException("Missing built-in shader " + id.getPath() + extension));
        }
        if (!ours) return null;
        return id.getPath().startsWith("shadow_iris_") ? shadowTerrain(current, id.getPath(), type) : standard(current, id.getPath(), type);
    }

    /**
     * A program of a standard pack, asked for as {@code metallum-extra:standard/<program>}; see {@link StandardPipeline}.
     * The dimension in use picks between a program's files in the dimension's folder and in the shared one.
     */
    private static String standard(final Lookup current, final String path, final ShaderType type) {
        // standard/<generation>/<program>: the generation says which plan the pipeline was made for.
        String[] parts = path.split("/");
        // Asking for the programs first brings the plan up to date, if the pack has changed since it was made.
        ProgramSet programs = IrisPipeline.programs();
        if (parts.length != 3 || Integer.parseInt(parts[1]) != IrisPipeline.generation()) {
            throw new StalePipelineException("The shader " + path + " belongs to a pack that is no longer in use");
        }
        String name = parts[2];
        ProgramSet.Program program = programs.find(name);
        String file = program == null ? null : type == ShaderType.VERTEX ? program.vertex() : program.fragment();
        if (file == null) throw new IllegalStateException("Shader pack " + current.pack.name() + " has no " + name + " program for this dimension");
        java.util.List<String> uniforms = IrisPipeline.uniformsOf(name);
        return current.files.computeIfAbsent(file, f -> {
            String text = readExpanded(current.pack, f);
            try {
                // Loose standard uniforms become one block (see IrisUniforms), the same one in both stages of the program.
                String rewritten = text == null ? null : IrisUniforms.rewrite(IrisPlan.withoutBufferFormats(text), uniforms);
                return Optional.ofNullable(rewritten == null ? null : debugPatch(name, type, rewritten));
            } catch (PackException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).orElseThrow();
    }

    /**
     * Development aid: {@code -Dmetallumextra.patch=<program>:<code>} runs the code after the main function of that program's fragment stage, so a program can be made
     * to show what it reads ({@code fragment_color = texelFetch(colortex1, ivec2(gl_FragCoord.xy), 0).rgb;}).
     */
    private static String debugPatch(final String program, final ShaderType type, final String text) {
        String patch = System.getProperty("metallumextra.patch");
        if (patch != null && patch.startsWith("@")) {
            try {
                patch = Files.readString(Path.of(patch.substring(1))).strip();
            } catch (IOException e) {
                return text;
            }
        }
        if (patch == null || type != ShaderType.FRAGMENT || !patch.startsWith(program + ":")) return text;
        java.util.regex.Matcher main = java.util.regex.Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)").matcher(text);
        if (!main.find()) return text;
        return main.replaceAll("void mx_patched_main()") + "\nvoid main() {\n    mx_patched_main();\n    " + patch.substring(program.length() + 1) + "\n}\n";
    }

    /** The text of a compute program, ready for the compiler: its includes and options in, the standard uniforms made one block. */
    public static String computeSource(final ProgramSet.Program program, final java.util.List<String> uniforms) {
        Lookup current = lookup;
        String text = readExpanded(current.pack, program.compute());
        if (text == null) throw new IllegalStateException("Shader pack " + current.pack.name() + " has no file " + program.compute());
        try {
            String rewritten = IrisUniforms.rewrite(IrisPlan.withoutBufferFormats(IrisWorldAdapter.declarationsWithoutComments(text)), uniforms);
            return GlslLocations.vulkanize(rewritten);
        } catch (PackException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Use this pack's files from now on. */
    public static void use(final ShaderPack pack) {
        lookup = new Lookup(pack, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    /** Forget what was read, so the files are read again. */
    public static void clear() {
        use(lookup.pack);
    }

    private static @Nullable String readExpanded(final ShaderPack pack, final String file) {
        return pack.read(file) == null ? null : pack.load(file);
    }

    private static void dump(final Identifier id, final ShaderType type, final @Nullable String text) {
        if (text == null) return;
        try {
            Path file = DUMP_DIR.resolve(id.getNamespace() + "." + id.getPath().replace('/', '.') + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
            if (!Files.exists(file)) {
                Files.createDirectories(DUMP_DIR);
                Files.writeString(file, text);
            }
        } catch (IOException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Could not dump shader {}", id, e);
        }
    }

    private static @Nullable Path dir(final String property) {
        String dir = System.getProperty(property);
        return dir == null || dir.isBlank() ? null : Path.of(dir);
    }
}
