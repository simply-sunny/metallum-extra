package com.metallumextra;

import com.metallumextra.shader.IrisTerrain;
import com.metallumextra.shader.IrisWorldAdapter;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ZipPack;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans a pack for a dimension, fits every program to the pipelines that would draw it the way the game does (see {@code ShaderSources}), and compiles each stage
 * with the game's own GLSL compiler. No game needed: {@code ./gradlew compileCheck -Pzip=<file> [-Pprofile=POTATO] [-Pdimension=world0] [-Pdump=<folder>]}.
 * Reports every failure with the program, stage and the compiler's own message, since a pack that plans can still not compile.
 */
public final class ShaderCompileCheck {
    private record Unit(String label, String vertex, String fragment) {
    }

    private ShaderCompileCheck() {
    }

    /**
     * {@code --all <folder>} checks every ZIP of a folder in this one JVM (starting Gradle and a JVM per pack is most of the time of a matrix run) and prints one
     * line per pack: {@code <file>|<result>}.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length > 1 && args[0].equals("--all")) {
            try (java.util.stream.Stream<Path> files = Files.list(Path.of(args[1]))) {
                for (Path zip : files.filter(f -> f.toString().endsWith(".zip")).sorted().toList()) {
                    String result;
                    java.io.PrintStream out = System.out;
                    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
                    System.setOut(new java.io.PrintStream(captured, true));
                    try {
                        run(new String[] {zip.toString()});
                        result = null;
                    } catch (Throwable e) {
                        result = e.getClass().getSimpleName() + ": " + e.getMessage();
                    } finally {
                        System.setOut(out);
                    }
                    String log = captured.toString();
                    String summary = log.lines().filter(l -> l.contains(" programs, ")).findFirst().orElse(null);
                    if (result == null) result = summary != null ? summary : "no result";
                    else if (summary != null) result = summary + "; " + result;
                    System.out.println(zip.getFileName() + "|" + result.replace('\n', ' '));
                }
            }
            return;
        }
        if (run(args) > 0) System.exit(1);
    }

    private static int run(final String[] args) throws Exception {
        com.metallumextra.shader.pack.ShaderPack pack = args[0].equals("builtin") ? new com.metallumextra.shader.pack.BuiltinPack() : ZipPack.open(Path.of(args[0]));
        String dimension = args.length > 1 ? args[1] : "world0";
        Path dump = args.length > 2 ? Path.of(args[2]) : null;
        IrisPlan plan = IrisPlan.build(pack, dimension);
        // The adapters look at the plan the game is using.
        Field field = com.metallumextra.shader.IrisPipeline.class.getDeclaredField("plan");
        field.setAccessible(true);
        field.set(null, plan);

        IrisWorldAdapter.Format format = new IrisWorldAdapter.Format(true, true, true, true, true, false, true, true, false, true);
        List<Unit> units = new ArrayList<>();
        for (ProgramSet.Stage stage : ProgramSet.Stage.values()) {
            for (IrisPlan.Step step : plan.steps(stage)) {
                ProgramSet.Program program = step.program();
                if (program.vertex() == null) continue;
                String vertex = IrisPlan.withoutBufferFormats(pack.load(program.vertex()));
                String fragment = IrisPlan.withoutBufferFormats(pack.load(program.fragment()));
                switch (stage) {
                    case GBUFFERS, SHADOW -> {
                        // As each kind of thing the plan draws with this program: terrain, or the game's own models.
                        boolean shadow = stage == ProgramSet.Stage.SHADOW;
                        for (IrisPlan.Layer layer : IrisPlan.Layer.values()) {
                            IrisPlan.Step used = shadow ? plan.shadowTerrainStep(layer) : plan.terrainStep(layer);
                            if (used != step) continue;
                            units.add(new Unit(program.name() + " as " + layer + " terrain", terrain(vertex, true, shadow, plan, step), terrain(fragment, false, shadow, plan, step)));
                        }
                        for (IrisPlan.Use use : IrisPlan.Use.values()) {
                            if (plan.worldStep(use) != step) continue;
                            if (use == IrisPlan.Use.SKY) {
                                units.add(new Unit(program.name() + " as the sky", finish(IrisWorldAdapter.adaptSky(vertex, true), true, step), finish(IrisWorldAdapter.remapOutputs(IrisWorldAdapter.adaptSky(fragment, false), step.writes(), plan, plan.alphaThreshold(program.name())), false, step)));
                                continue;
                            }
                            boolean shadowUse = use == IrisPlan.Use.SHADOW_ENTITY || use == IrisPlan.Use.SHADOW_BLOCK;
                            IrisWorldAdapter.Format f = use == IrisPlan.Use.CLOUDS ? new IrisWorldAdapter.Format(true, false, false, false, false, false, false, false, true, false) : format;
                            units.add(new Unit(program.name() + " as " + use, finish(IrisWorldAdapter.adapt(vertex, true, f, shadowUse), true, step),
                                    finish(IrisWorldAdapter.remapOutputs(IrisWorldAdapter.adapt(fragment, false, f, shadowUse), step.writes(), shadowUse ? null : plan, plan.alphaThreshold(program.name())), false, step)));
                        }
                    }
                    default -> units.add(new Unit(program.name(), finishStandard(vertex, step), finishStandard(fragment, step)));
                }
            }
        }

        int failed = 0;
        for (Unit unit : units) {
            for (boolean isVertex : new boolean[] {true, false}) {
                // The game's compiler wants every input and output numbered; ShaderSources does that to the two stages of a program together.
                String source = com.metallumextra.shader.GlslLocations.fit(isVertex ? unit.vertex : unit.fragment, isVertex ? unit.fragment : unit.vertex, isVertex);
                String name = unit.label + (isVertex ? " (vertex)" : " (fragment)");
                if (dump != null) {
                    Files.createDirectories(dump);
                    Files.writeString(dump.resolve(name.replaceAll("[^A-Za-z0-9_.-]+", "_") + (isVertex ? ".vsh" : ".fsh")), source);
                }
                String error = compile(name, source, isVertex);
                if (error == null && unit.label.contains(" as ")) error = unboundSampler(source);
                if (error == null) {
                    System.out.println("ok    " + name);
                } else {
                    failed++;
                    System.out.println("FAIL  " + name + ": " + firstLines(error));
                }
            }
        }
        System.out.println(units.size() + " programs, " + failed + " stage(s) failed to compile");
        return failed;
    }

    /**
     * Compiles with the options the game's own GLSL compiler uses (Vulkan 1.3 SPIR-V, automatic bindings), which that class does not let a test do:
     * it asks the graphics device, and there is none. Returns the compiler's message, or null if the stage compiles.
     */
    private static String compile(final String name, final String source, final boolean isVertex) {
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
        Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
        Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
        Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
        long compiler = Shaderc.shaderc_compiler_initialize();
        try {
            // Not as strings: a big program does not fit LWJGL's small stack, which converts them.
            java.nio.ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
            java.nio.ByteBuffer nameBuffer = MemoryUtil.memUTF8(name);
            java.nio.ByteBuffer entryBuffer = MemoryUtil.memUTF8("main");
            long result = Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, isVertex ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader, nameBuffer, entryBuffer, options);
            MemoryUtil.memFree(entryBuffer);
            MemoryUtil.memFree(nameBuffer);
            MemoryUtil.memFree(sourceBuffer);
            try {
                return Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success ? null : Shaderc.shaderc_result_get_error_message(result);
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
            Shaderc.shaderc_compile_options_release(options);
        }
    }

    private static String terrain(final String source, final boolean vertex, final boolean shadow, final IrisPlan plan, final IrisPlan.Step step) throws PackException {
        String adapted = IrisTerrain.adapt(source, vertex, shadow, "true".equals(plan.programs().property("mx.blockTypes")), plan.usesBlockIds());
        return finish(vertex ? adapted : IrisWorldAdapter.remapOutputs(adapted, step.writes(), shadow ? null : plan, step == plan.terrainStep(IrisPlan.Layer.SOLID) ? plan.terrainAlphaThreshold(step.program().name(), IrisPlan.Layer.SOLID) : plan.alphaThreshold(step.program().name())), vertex, step);
    }

    private static String finish(final String adapted, final boolean vertex, final IrisPlan.Step step) throws PackException {
        // The programs that draw the world get the uniforms found in their adapted source (see ShaderSources).
        return IrisUniforms.rewrite(adapted, IrisUniforms.declared(adapted));
    }

    /** A full-screen program gets the uniforms the plan found in it (the plan reads its source before it is adapted). */
    private static String finishStandard(final String source, final IrisPlan.Step step) throws PackException {
        return IrisUniforms.rewrite(source, step.uniforms());
    }

    private static final java.util.regex.Pattern SAMPLER = java.util.regex.Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?[iu]?sampler\\w*\\s+(\\w+)\\s*;");

    /** The game's pipeline compiler refuses a shader that reads a sampler its pipeline does not declare; the ones a pipeline can have are its own and the {@code Mx} names. */
    private static String unboundSampler(final String source) {
        java.util.regex.Matcher match = SAMPLER.matcher(com.metallumextra.shader.pack.LegacyGlsl.blankComments(source));
        while (match.find()) {
            String name = match.group(1);
            if (!name.startsWith("Mx") && !java.util.Set.of("Sampler0", "Sampler1", "Sampler2", "u_BlockTex", "u_LightTex", "u_SectionTimeInfo", "CloudFaces").contains(name)) {
                return "the shader declares the sampler " + name + ", which no pipeline binds";
            }
        }
        return null;
    }

    private static String firstLines(final String message) {
        if (message == null) return "(no message)";
        String[] lines = message.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 6); i++) out.append("\n      ").append(lines[i]);
        return out.toString();
    }
}
