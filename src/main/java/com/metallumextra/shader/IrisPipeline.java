package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs a standard (Iris layout) shader pack: the programs of its {@code begin}, {@code prepare}, {@code deferred},
 * {@code composite} and {@code final} stages, over the buffers of {@link IrisTargets}, following the plan that
 * {@link IrisPlan} worked out when the pack was loaded.
 * <p>
 * Where each stage runs in a frame:
 * <pre>
 * beforeWorldPasses     begin, prepare            (nothing has been drawn; no render pass is open)
 * ... the game draws the sky and the opaque world ...
 * beforeTranslucent     deferred                  (the world so far is colortex0; the result goes back into the world)
 * ... the game draws translucent terrain ...
 * afterWorld            composite, final          (the finished world is colortex0; final draws to the screen)
 * ... the game draws the held item and the interface ...
 * </pre>
 * The game's own shaders still draw the world: a pack's {@code gbuffers_*} and {@code shadow} programs need the
 * vertex inputs of the next milestone and are not run (they are listed in the log). The world reaches the pack as a copy of
 * the game's image in colortex0 and of its depth in depthtex0 and depthtex1. Built-in effects are off while a standard
 * pack is in use.
 */
public final class IrisPipeline {
    private static final Identifier BLIT_VERTEX = Identifier.fromNamespaceAndPath(com.metallumextra.MetallumExtra.MOD_ID, "internal/fullscreen");
    private static final Identifier BLIT_FRAGMENT = Identifier.fromNamespaceAndPath(com.metallumextra.MetallumExtra.MOD_ID, "internal/blit");

    private static final IrisTargets TARGETS = new IrisTargets();
    private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<GpuFormat, RenderPipeline> BLITS = new HashMap<>();
    private static final Set<String> REPORTED = new HashSet<>();

    /** Counts the plans built; a pipeline made for an earlier plan is stale (see {@link StalePipelineException}). */
    private static int generation;
    private static @Nullable IrisPlan plan;
    private static @Nullable ShaderPack planPack;
    private static @Nullable String planDimension;
    private static float[] fog = {0, 0, 0, 1};

    /** Development aid: how many more frames to log every program run and the pixels it wrote; see the debug script's {@code trace}. */
    private static int traceFrames;
    private static int frame;
    private static int passes;
    private static int copies;
    private static int lastPasses;
    private static boolean worldCopied;

    private IrisPipeline() {
    }

    /** Whether a standard pack is in use, whose programs run in place of the built-in effects. */
    public static boolean inUse() {
        return Shaders.active() && PackManager.active().standard();
    }

    /** The fog color of this frame, which buffer 0 is cleared to unless the pack says otherwise. */
    static void setFog(final float red, final float green, final float blue) {
        fog = new float[] {red, green, blue, 1.0F};
    }

    /** Logs every program run for the next {@code frames} frames, with the pixels each wrote. */
    public static void trace(final int frames) {
        traceFrames = frames;
    }

    /** What the pipeline has done, for the debug script. */
    public static String summary() {
        return "iris: " + lastPasses + " passes and " + copies + " copies in the last frame, " + TARGETS.textures() + " textures ("
                + TARGETS.bytes() / (1024 * 1024) + " MB), " + TARGETS.created() + " created in all";
    }

    private static String dimensionFolder() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : ProgramSet.folderOf(minecraft.level.dimension().identifier().toString());
    }

    /** The plan for the pack in use and the dimension the player is in; null if the pack could not be planned (it is then put aside). */
    private static @Nullable IrisPlan plan() {
        ShaderPack pack = PackManager.active();
        String dimension = dimensionFolder();
        if (plan == null || pack != planPack || !java.util.Objects.equals(dimension, planDimension)) {
            try {
                IrisPlan built = IrisPlan.build(pack, dimension);
                PIPELINES.clear();
                plan = built;
                generation++;
                planPack = pack;
                planDimension = dimension;
                if (REPORTED.add(pack.name() + "/" + dimension)) {
                    for (String note : built.notes()) MetallumExtra.LOGGER.info("[Metallum Extra] Shader pack {}: {}", pack.name(), note);
                    MetallumExtra.LOGGER.info("[Metallum Extra] Shader pack {}: {} programs, {} buffers", pack.name(), built.stepCount(), built.buffers().size());
                }
            } catch (PackException e) {
                plan = null;
                PackManager.fail(e);
                return null;
            }
        }
        return plan;
    }

    /** The generation of the plan that is current; the shader ids of its programs carry it. */
    public static int generation() {
        return generation;
    }

    /** The programs of the pack in use for the dimension the player is in. */
    public static ProgramSet programs() {
        IrisPlan current = plan();
        return current == null ? ProgramSet.discover(PackManager.active(), dimensionFolder()) : current.programs();
    }

    /** The pack or the dimension changed, or shaders were reloaded: everything is planned and built again, and every buffer freed. */
    public static void forget() {
        plan = null;
        planPack = null;
        PIPELINES.clear();
        BLITS.clear();
        TARGETS.release();
    }

    // ---- the stages ----

    /** Start of the world's drawing: the buffers are made ready and cleared, then the begin and prepare programs run. */
    static void beforeWorld() {
        guarded(() -> {
            IrisPlan current = plan();
            if (current == null) return;
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            TARGETS.configure(current, main.width, main.height);
            frame++;
            lastPasses = passes;
            passes = 0;
            copies = 0;
            worldCopied = false;
            TARGETS.clearForFrame(fog);
            run(current.steps(ProgramSet.Stage.BEGIN), main);
            run(current.steps(ProgramSet.Stage.PREPARE), main);
        });
    }

    /** Before translucent terrain: what has been drawn so far, which is the opaque world. */
    static void beforeTranslucent() {
        guarded(() -> {
            IrisPlan current = plan();
            if (current == null || TARGETS.width() == 0) return;
            List<IrisPlan.Step> deferred = current.steps(ProgramSet.Stage.DEFERRED);
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (!ready(main)) return;
            TARGETS.snapshotDepth(main, false);
            if (current.usesDepth(1) || current.usesDepth(2)) copies++;
            if (deferred.isEmpty()) return;
            worldIntoColor0(main);
            run(deferred, main);
            colorZeroIntoWorld(main);
        });
    }

    /** The world is finished: composite programs, then the final one, which draws to the screen. */
    static void afterWorld() {
        guarded(() -> {
            IrisPlan current = plan();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (current == null || !current.afterWorld() || !ready(main)) return;
            TARGETS.snapshotDepth(main, true);
            if (current.usesDepth(0)) copies++;
            if (current.steps(ProgramSet.Stage.DEFERRED).isEmpty() || !worldCopied) worldIntoColor0(main);
            run(current.steps(ProgramSet.Stage.COMPOSITE), main);
            IrisPlan.Step last = current.finalStep();
            if (last != null) {
                draw(last, main);
            } else if (!current.steps(ProgramSet.Stage.COMPOSITE).isEmpty()) {
                // Without a final program the screen shows buffer 0.
                colorZeroIntoWorld(main);
            }
            worldCopied = false;
            if (traceFrames > 0) traceFrames--;
        });
    }

    private static boolean ready(final RenderTarget main) {
        return TARGETS.width() == Math.max(main.width, 1) && TARGETS.height() == Math.max(main.height, 1);
    }

    /** Runs a stage's programs: each draws, then the buffers it drew into are flipped. */
    private static void run(final List<IrisPlan.Step> steps, final RenderTarget main) {
        for (IrisPlan.Step step : steps) draw(step, main);
    }

    private static void draw(final IrisPlan.Step step, final RenderTarget main) {
        ProgramSet.Program program = step.program();
        boolean isFinal = program.stage() == ProgramSet.Stage.FINAL;
        RenderPipeline pipeline = PIPELINES.computeIfAbsent(program.name(), name -> build(step));

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Metallum Extra " + program.name())
                .withRenderArea(new RenderPass.RenderArea(0, 0, TARGETS.width(), TARGETS.height()));
        if (isFinal) {
            descriptor.withColorAttachment(main.getColorTextureView());
        } else {
            for (int i = 0; i < step.writes().length; i++) descriptor.withColorAttachment(TARGETS.write(step.writes()[i], step.inPlace()[i]));
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            for (IrisPlan.Input input : step.inputs()) {
                if (input.colorBuffer() >= 0) pass.bindTexture(input.name(), TARGETS.read(input.colorBuffer()), linear);
                else pass.bindTexture(input.name(), TARGETS.depth(input.depthTexture() == 0 ? 0 : 1), nearest);
            }
            pass.draw(3, 1, 0, 0);
        }
        passes++;
        for (int i = 0; i < step.writes().length; i++) {
            if (!step.inPlace()[i]) TARGETS.flip(step.writes()[i]);
        }
        if (traceFrames > 0) trace(step);
    }

    private static RenderPipeline build(final IrisPlan.Step step) {
        ProgramSet.Program program = step.program();
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (IrisPlan.Input input : step.inputs()) layout.withSampler(input.name());
        Identifier id = Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "standard/" + generation + "/" + program.name());
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/standard/" + generation + "/" + program.name()))
                .withVertexShader(id)
                .withFragmentShader(id)
                .withBindGroupLayout(layout.build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (program.stage() == ProgramSet.Stage.FINAL) {
            builder.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR));
        } else {
            for (int i = 0; i < step.writes().length; i++) {
                builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), TARGETS.spec(step.writes()[i]).format(), ColorTargetState.WRITE_ALL));
            }
        }
        return builder.build();
    }

    // ---- moving the world's image between the game's target and buffer 0 ----

    private static void worldIntoColor0(final RenderTarget main) {
        if (!TARGETS.hasBuffer(0)) return;
        GpuFormat format = TARGETS.spec(0).format();
        if (format == GpuFormat.RGBA8_UNORM) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), TARGETS.readTarget(0).getColorTexture(), 0, 0, 0, 0, 0, TARGETS.width(), TARGETS.height());
        } else {
            blit(main.getColorTextureView(), TARGETS.read(0), format);
        }
        copies++;
        worldCopied = true;
    }

    private static void colorZeroIntoWorld(final RenderTarget main) {
        if (!TARGETS.hasBuffer(0)) return;
        if (TARGETS.spec(0).format() == GpuFormat.RGBA8_UNORM) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(TARGETS.readTarget(0).getColorTexture(), main.getColorTexture(), 0, 0, 0, 0, 0, TARGETS.width(), TARGETS.height());
        } else {
            blit(TARGETS.read(0), main.getColorTextureView(), GpuFormat.RGBA8_UNORM);
        }
        copies++;
    }

    /** Draws one image into another of a different format, which a plain copy cannot do. */
    private static void blit(final com.mojang.blaze3d.textures.GpuTextureView from, final com.mojang.blaze3d.textures.GpuTextureView to, final GpuFormat format) {
        RenderPipeline pipeline = BLITS.computeIfAbsent(format, f -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/internal/blit_" + f.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(BLIT_VERTEX)
                .withFragmentShader(BLIT_FRAGMENT)
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("colortex0").build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Metallum Extra blit", to, Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("colortex0", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        passes++;
    }

    // ---- failure and tracing ----

    /** A pack that breaks while it runs is put aside, and the one before it (or the built-in shaders) takes over. */
    private static void guarded(final Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            // A pipeline of the pack that has just been put aside: its frame is dropped, and the pack now in use is innocent.
            for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
                if (cause instanceof StalePipelineException) {
                    forget();
                    return;
                }
            }
            if (!PackManager.fail(e)) throw e;
        }
    }

    /**
     * Development aid: logs the program and what it drew, for tests. The pixels are read back from the texture the program
     * drew into (now the one that is read), at five places across the screen; only 8-bit buffers are read.
     */
    private static void trace(final IrisPlan.Step step) {
        ProgramSet.Program program = step.program();
        MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} stage {} program {} writes {} reads {}", frame, program.stage().name().toLowerCase(java.util.Locale.ROOT),
                program.name(), java.util.Arrays.toString(step.writes()), step.inputs().stream().map(IrisPlan.Input::name).toList());
        int number = frame;
        for (int index : step.writes()) {
            if (TARGETS.spec(index).format() != GpuFormat.RGBA8_UNORM) {
                MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} program {} colortex{} not read back ({})", number, program.name(), index, TARGETS.spec(index).format());
                continue;
            }
            int buffer = index;
            Screenshot.takeScreenshot(TARGETS.readTarget(index), image -> {
                try (image) {
                    int w = image.getWidth(), h = image.getHeight();
                    int[][] points = {{w / 2, h / 2}, {w / 4, h / 2}, {3 * w / 4, h / 2}, {w / 2, h / 4}, {w / 2, 3 * h / 4}};
                    StringBuilder text = new StringBuilder();
                    for (int[] point : points) {
                        int argb = image.getPixel(point[0], point[1]);
                        text.append(String.format(java.util.Locale.ROOT, " (%d,%d,%d,%d)", (argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255));
                    }
                    MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} program {} colortex{} {}x{} pixels{}", number, program.name(), buffer, w, h, text);
                }
            });
        }
    }
}
