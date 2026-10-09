package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.BuiltinPack;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GLSL of the shader pack in use, looked up by the same id the game or Sodium asks its own shaders by. The pack
 * is the built-in one (the jar) or a ZIP in the shaderpacks folder; see {@link PackManager}. Nothing is ever taken
 * from any pack but the one in use.
 * <p>
 * Two kinds of file live under a pack's {@code shaders/} folder (for the built-in pack,
 * {@code assets/metallum-extra/shaders/}):
 * <ul>
 * <li>{@code override/<namespace>/<path>.vsh|.fsh} replaces the shader another mod or the game registered under
 * {@code <namespace>:<path>} while shaders are on. The pipeline stays theirs; only its text changes.</li>
 * <li>{@code program/<path>.vsh|.fsh} is a shader of this mod's own pipelines, asked for as {@code metallum-extra:<path>}.</li>
 * </ul>
 * A line {@code #include "name.glsl"} is replaced by {@code lib/name.glsl} the first time a shader asks for
 * that file, and dropped after that, so library files can include what they need. Every file must start with its
 * {@code #version} line, since the game inserts a pipeline's defines right after the first line.
 */
public final class ShaderSources {
    /** Development aid: every shader the game compiles (as other mods left it) is written to this folder. */
    private static final @Nullable Path DUMP_DIR = dir("metallumextra.dumpShaders");

    /** What has been read from one pack. A new one replaces it whenever the pack changes, so a stale entry cannot outlive its pack. */
    private record Lookup(ShaderPack pack, Map<String, Optional<String>> files) {
    }

    private static final ShaderPack BUILTIN = new BuiltinPack();
    private static final Map<String, Optional<String>> INTERNAL = new ConcurrentHashMap<>();

    private static volatile Lookup lookup = new Lookup(new BuiltinPack(), new ConcurrentHashMap<>());

    private ShaderSources() {
    }

    /** Wraps the source the game compiles from, so this mod's shaders are found first while shaders are on. */
    public static ShaderSource wrap(final ShaderSource original) {
        return (id, type) -> {
            String ours = Shaders.active() ? get(id, type) : null;
            Identifier real = isTerrainProgram(id) ? Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque") : id;
            if (DUMP_DIR != null) dump(id, type, original.get(real, type));
            // A terrain pipeline made for a pack that has since gone (Sodium keeps them) compiles as Sodium's own shader until it is replaced.
            return ours != null ? ours : original.get(real, type);
        };
    }

    /** Whether the id is one of {@link IrisPipeline#terrainShaderId}'s: Sodium's terrain shader replaced by a standard pack's program. */
    private static boolean isTerrainProgram(final Identifier id) {
        return id.getNamespace().equals("sodium") && id.getPath().startsWith("blocks/iris_");
    }

    /**
     * A standard pack's program for a layer of terrain, fitted to Sodium's pipeline; null if the id belongs to a plan that is no longer
     * current. The id is {@code blocks/iris_<generation>_<program>}.
     */
    private static @Nullable String terrain(final Lookup current, final Identifier id, final ShaderType type) {
        String[] parts = id.getPath().substring("blocks/iris_".length()).split("_", 2);
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null || parts.length != 2 || Integer.parseInt(parts[0]) != IrisPipeline.generation()) return null;
        IrisPlan.Step step = plan.step(parts[1]);
        ProgramSet.Program program = step == null ? null : step.program();
        String file = program == null ? null : type == ShaderType.VERTEX ? program.vertex() : program.fragment();
        if (file == null) return null;
        return current.files.computeIfAbsent(file, f -> {
            String text = readExpanded(current.pack, f);
            try {
                if (text == null) return Optional.empty();
                String adapted = IrisTerrain.adapt(IrisPlan.withoutBufferFormats(text), type == ShaderType.VERTEX);
                return Optional.of(IrisUniforms.rewrite(adapted, step.uniforms()));
            } catch (PackException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).orElseThrow();
    }

    public static @Nullable String get(final Identifier id, final ShaderType type) {
        PackManager.start();
        Lookup current = lookup;
        if (isTerrainProgram(id)) return current.pack.standard() ? terrain(current, id, type) : null;
        String extension = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
        boolean ours = id.getNamespace().equals(MetallumExtra.MOD_ID);
        if (ours && id.getPath().startsWith("internal/")) {
            // The mod's own helper programs, which no pack provides or replaces.
            return INTERNAL.computeIfAbsent(id.getPath() + extension, f -> Optional.ofNullable(readExpanded(BUILTIN, f))).orElseThrow(
                    () -> new IllegalStateException("Missing built-in shader " + id.getPath() + extension));
        }
        if (ours && current.pack.standard()) {
            return standard(current, id.getPath(), type);
        }
        String file = ours
                ? "program/" + id.getPath() + extension
                : "override/" + id.getNamespace() + "/" + id.getPath() + extension;
        String text = current.files.computeIfAbsent(file, f -> Optional.ofNullable(readExpanded(current.pack, f))).orElse(null);
        // A shader of this mod's own passes that the pack lacks cannot be borrowed from anywhere else.
        if (text == null && ours) {
            throw new IllegalStateException("Shader pack " + current.pack.name() + " has no " + file);
        }
        return text;
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
                return Optional.ofNullable(text == null ? null : IrisUniforms.rewrite(IrisPlan.withoutBufferFormats(text), uniforms));
            } catch (PackException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).orElseThrow();
    }

    /** Use this pack's files from now on. */
    public static void use(final ShaderPack pack) {
        lookup = new Lookup(pack, new ConcurrentHashMap<>());
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
