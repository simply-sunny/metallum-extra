package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.metallumextra.shader.pack.IrisUniforms;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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
    private static final IrisUniformValues UNIFORMS = new IrisUniformValues();
    private static java.util.Map<String, double[]> uniformValues = java.util.Map.of();
    private static @Nullable GpuBuffer uniformBuffer;
    private static @Nullable GpuBuffer depthParams;
    private static @Nullable RenderPipeline depthPipeline;
    private static final long START = System.nanoTime();
    private static final boolean FROZEN_TIME = Boolean.getBoolean("metallumextra.freezeTime");
    private static long lastFrameAt;
    private static long frameCounter;
    private static double frameSeconds = 1.0 / 60.0;
    private static float far = 128.0F;
    private static final org.joml.Matrix4f GAME_PROJECTION = new org.joml.Matrix4f();
    private static int frame;
    private static int passes;
    private static int copies;
    private static int lastPasses;
    private static boolean worldCopied;

    private IrisPipeline() {
    }

    /** Whether a standard pack is in use, whose programs run in place of the built-in effects. */
    public static boolean inUse() {
        return Shaders.active();
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
                // Sodium keeps its terrain pipelines, which carry the plan's shader ids and buffers.
                com.metallumextra.shader.sodium.SodiumTerrain.resetPipelines();
                com.metallumextra.shader.sodium.ShadowTerrain.forgetPipelines();
                ShadowPass.invalidate();
                IrisWorld.forget();
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

    /** The standard uniforms the program of this name declares (what its block holds); empty when it has none. */
    public static List<String> uniformsOf(final String program) {
        IrisPlan current = plan();
        IrisPlan.Step step = current == null ? null : current.step(program);
        return step == null ? List.of() : step.uniforms();
    }

    /** The tilt of the sun's path the pack asked for, in degrees. */
    static float sunPathRotation() {
        return plan == null ? 0.0F : plan.sunPathRotation();
    }

    /** The current plan, or null if there is none: it is made when a frame starts, so what Sodium asks during the frame agrees with it. */
    public static @Nullable IrisPlan currentPlan() {
        return plan;
    }

    // ---- the world: Sodium and the game draw it with the pack's programs ----

    private static boolean inStage;

    /** Whether this class is running a stage right now, so the passes it makes are its own and get nothing added. */
    static boolean inStage() {
        return inStage;
    }

    /**
     * The id of the shader for a layer of terrain, which Sodium builds its pipeline with in place of its own; null when the pack has no
     * program for the layer. The id names the program and the plan it comes from ({@code sodium:blocks/iris_<generation>_<program>}),
     * and is read back by {@link ShaderSources}. Sodium keeps its pipelines, so they are thrown away whenever the plan changes.
     */
    public static net.minecraft.resources.@Nullable Identifier terrainShaderId(final IrisPlan.Layer layer) {
        IrisPlan current = inUse() ? plan : null;
        IrisPlan.Step step = current == null ? null : current.terrainStep(layer);
        return step == null ? null : net.minecraft.resources.Identifier.fromNamespaceAndPath("sodium", "blocks/iris_" + generation + "_" + step.program().name());
    }

    /**
     * Called for every render pass about to be made. While the world is drawn, a pass into the game's own image gets the other buffers the
     * pack's programs write, each in the attachment of its number (see {@link IrisWorld}); the pipelines used in it declare the same.
     */
    public static void attachWorldTargets(final RenderPassDescriptor descriptor) {
        if (inStage || !inUse() || plan == null || plan.worldBuffers().isEmpty() || descriptor.colorAttachments().size() != 1) return;
        int phase = Shaders.phase();
        if (phase != Shaders.PHASE_WORLD && phase != Shaders.PHASE_HAND) return;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (TARGETS.width() == 0 || !ready(main) || descriptor.colorAttachments().get(0) == null
                || descriptor.colorAttachments().get(0).textureView().texture() != main.getColorTexture()) return;
        int last = java.util.Collections.max(plan.worldBuffers());
        for (int slot = 1; slot <= last; slot++) {
            if (plan.worldBuffers().contains(slot)) descriptor.withColorAttachment(TARGETS.read(slot));
            else descriptor.withUnusedColorAttachment();
        }
    }

    /** Whether a program of the pack in use reads the grid of block light colors. */
    static boolean usesLightColors() {
        return plan != null && plan.usesLightColors();
    }

    /** Whether the pack in use has a program for the sky, which then replaces the game's own sky disc. */
    static boolean drawsSky() {
        return plan != null && plan.worldStep(IrisPlan.Use.SKY) != null;
    }

    /** The buffers, for the shadow pass. */
    static IrisTargets targets() {
        return TARGETS;
    }

    /** Makes the plan for the pack in use now if there is none, so a frame's setup (the shadow matrices) can follow it. */
    static void ensurePlan() {
        if (inUse()) plan();
    }

    /** The shadow map's depth as a program reads it: an empty one while the map is being drawn, and when there is none. */
    static com.mojang.blaze3d.textures.GpuTextureView shadowDepth() {
        TextureTarget shadow = Shaders.phase() == Shaders.PHASE_SHADOW ? null : TARGETS.shadow();
        TextureTarget use = shadow != null ? shadow : TARGETS.emptyShadow();
        return use != null ? use.getDepthTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    static com.mojang.blaze3d.textures.GpuTextureView shadowColor() {
        TextureTarget shadow = Shaders.phase() == Shaders.PHASE_SHADOW ? null : TARGETS.shadow();
        TextureTarget use = shadow != null ? shadow : TARGETS.emptyShadow();
        return use != null ? use.getColorTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    /** The world as it was before the translucent things (water, glass) were drawn: what their programs read as {@code colortex0}. */
    static com.mojang.blaze3d.textures.GpuTextureView worldColor() {
        return TARGETS.hasBuffer(0) ? TARGETS.read(0) : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    /** The depth of the world before the translucent things were drawn: {@code depthtex0} and {@code depthtex1} of their programs. */
    static com.mojang.blaze3d.textures.GpuTextureView worldDepth() {
        TextureTarget target = TARGETS.depthTarget(false);
        return target != null ? target.getDepthTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
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
        IrisWorld.forget();
        com.metallumextra.shader.sodium.SodiumTerrain.resetPipelines();
        com.metallumextra.shader.sodium.ShadowTerrain.forgetPipelines();
        ShadowPass.invalidate();
        PIPELINES.clear();
        BLITS.clear();
        TARGETS.release();
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
        depthPipeline = null;
    }

    // ---- the stages ----

    /**
     * Start of the world's drawing: the buffers are made ready and cleared, the begin programs run, the shadow map is drawn, then the
     * prepare programs run. The shadow map is drawn outside {@link #guarded}: it draws the game's own models, whose pipelines are swapped
     * for the pack's copies only while this class is not in the middle of a stage.
     */
    static void beforeWorld(final net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame features) {
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
            captureUniforms(current, main);
            run(current.steps(ProgramSet.Stage.BEGIN), main);
        });
        IrisPlan current = plan;
        if (current != null && current.hasShadow() && TARGETS.width() > 0) {
            try {
                ShadowPass.render(features);
            } catch (RuntimeException e) {
                Shaders.setPhase(Shaders.PHASE_WORLD);
                handleFailure(e);
            }
        }
        guarded(() -> {
            IrisPlan again = plan;
            if (again == null) return;
            run(again.steps(ProgramSet.Stage.PREPARE), Minecraft.getInstance().gameRenderer.mainRenderTarget());
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
            snapshotDepth(main, false);
            if (current.usesDepth(1) || current.usesDepth(2)) copies++;
            // The programs that draw translucent things read the world behind them as colortex0 (water refracts it).
            boolean read = current.translucentReadsColor();
            if (deferred.isEmpty() && !read) return;
            worldIntoColor0(main);
            if (deferred.isEmpty()) return;
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
            snapshotDepth(main, true);
            if (current.usesDepth(0)) copies++;
            // Translucent things were drawn into the game's image after the deferred programs: it, not buffer 0, is the finished world.
            worldIntoColor0(main);
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

        int areaWidth = isFinal ? TARGETS.width() : TARGETS.widthOf(step.writes()[0]);
        int areaHeight = isFinal ? TARGETS.height() : TARGETS.heightOf(step.writes()[0]);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Metallum Extra " + program.name())
                .withRenderArea(new RenderPass.RenderArea(0, 0, areaWidth, areaHeight));
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
                else if (input.colorBuffer() == IrisPlan.SHADOW_DEPTH) pass.bindTexture(input.name(), shadowDepth(), nearest);
                else if (input.colorBuffer() == IrisPlan.SHADOW_COLOR) pass.bindTexture(input.name(), shadowColor(), linear);
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

    // ---- the standard uniforms ----

    /** Works out this frame's values of the standard uniforms and writes each program's block. Called with no render pass open. */
    private static void captureUniforms(final IrisPlan current, final RenderTarget main) {
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        var state = minecraft.gameRenderer.gameRenderState();
        var camera = state.levelRenderState.cameraRenderState;
        var sky = state.levelRenderState.skyRenderState;
        var lightmap = state.lightmapRenderState;
        long now = System.nanoTime();
        frameSeconds = lastFrameAt == 0 ? 1.0 / 60.0 : (now - lastFrameAt) / 1.0e9;
        lastFrameAt = now;
        far = minecraft.options.getEffectiveRenderDistance() * 16.0F;
        GAME_PROJECTION.set(Shaders.globals().projection);
        int skyColor = sky.skyColor;
        var player = minecraft.player;
        var inputs = new IrisUniformValues.Inputs(new org.joml.Matrix4f(Shaders.globals().view), new org.joml.Matrix4f(GAME_PROJECTION), far,
                camera.pos.x, camera.pos.y, camera.pos.z, sky.sunAngle, sky.moonAngle, Shaders.sunPathTilt(),
                level.getOverworldClockTime(), sky.moonPhase.index(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F),
                FROZEN_TIME ? 0.0 : (now - START) / 1.0e9, frameSeconds, frameCounter++, main.width, main.height,
                (float) (double) minecraft.options.gamma().get(), lightmap.nightVisionEffectIntensity,
                player != null && player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) ? 1.0F : 0.0F, lightmap.darknessEffectScale,
                camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z,
                ((skyColor >> 16) & 255) / 255.0F, ((skyColor >> 8) & 255) / 255.0F, (skyColor & 255) / 255.0F,
                level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, camera.blockPos), level.getBrightness(net.minecraft.world.level.LightLayer.SKY, camera.blockPos),
                switch (camera.fogType) {
                    case WATER -> 1;
                    case LAVA -> 2;
                    case POWDER_SNOW -> 3;
                    default -> 0;
                });
        uniformValues = new HashMap<>(UNIFORMS.frame(inputs));
        addShadowAndExtensions(uniformValues, false);
        if (traceFrames > 0) {
            MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} game projection: m00 {} m11 {} m22 {} m32 {} m23 {} m33 {} m20 {} m21 {} far {}", frame, GAME_PROJECTION.m00(), GAME_PROJECTION.m11(),
                    GAME_PROJECTION.m22(), GAME_PROJECTION.m32(), GAME_PROJECTION.m23(), GAME_PROJECTION.m33(), GAME_PROJECTION.m20(), GAME_PROJECTION.m21(), far);
        }
        writeUniforms();
    }

    private static void writeUniforms() {
        IrisUniforms.Layout layout = IrisUniforms.layout();
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(layout.size()).order(java.nio.ByteOrder.nativeOrder());
        IrisUniformValues.write(layout, uniformValues, data);
        data.position(0).limit(layout.size());
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniformBuffer().slice(), data);
    }

    /**
     * The values of the shadow map's matrices and of the built-in shader's lighting model (the {@code Mx} uniforms, which are not Iris's),
     * written into the frame's values. Called with no render pass open.
     *
     * @param atRefresh the light's view as the cached terrain is drawn from, not as the player sees it now
     */
    private static void addShadowAndExtensions(final Map<String, double[]> values, final boolean atRefresh) {
        org.joml.Matrix4f view = ShadowPass.shadowView(atRefresh);
        org.joml.Matrix4f projection = ShadowPass.shadowProjection();
        IrisUniformValues.put(values, "shadowModelView", view);
        IrisUniformValues.put(values, "shadowModelViewInverse", new org.joml.Matrix4f(view).invert());
        IrisUniformValues.put(values, "shadowProjection", projection);
        IrisUniformValues.put(values, "shadowProjectionInverse", new org.joml.Matrix4f(projection).invert());
        ShaderGlobals g = Shaders.globals();
        values.put("MxLightDir", vec4(g.lightDir));
        values.put("MxLightColor", vec4(g.lightColor));
        values.put("MxSunDir", vec4(g.sunDir));
        values.put("MxMoonDir", vec4(g.moonDir));
        values.put("MxSkyAmbient", vec4(g.skyAmbient));
        values.put("MxBlockLight", vec4(g.blockLight));
        values.put("MxMinAmbient", vec4(g.minAmbient));
        values.put("MxSkyZenith", vec4(g.skyZenith));
        values.put("MxSkyHorizon", vec4(g.skyHorizon));
        values.put("MxSunsetColor", vec4(g.sunsetColor));
        values.put("MxLightGrid", vec4(g.lightGrid));
        values.put("MxEyeSky", new double[] {g.eyeSky});
        values.put("MxFlags", new double[] {Shaders.celestialLight() ? 1.0 : 0.0, Shaders.celestialLight() ? 1.0 : 1.5, 0.0, 0.0});
        values.put("MxFog", vec4(g.fog));
        values.put("MxFogEnds", vec4(g.fogEnds));
        values.put("MxFogColor", vec4(g.fogColor));
    }

    private static double[] vec4(final org.joml.Vector4f v) {
        return new double[] {v.x, v.y, v.z, v.w};
    }

    /** The shadow pass's own values of the block: see {@link #addShadowAndExtensions}. */
    static void writeShadowUniforms(final boolean atRefresh) {
        addShadowAndExtensions(uniformValues, atRefresh);
        writeUniforms();
    }

    /**
     * The one buffer behind the {@value IrisUniforms#BLOCK} block of every program, those of this pipeline and the game's own pipelines
     * that run a pack's shaders alike: the block always has every member (see {@link IrisUniforms#rewrite}), so one layout fits all.
     */
    public static GpuBuffer uniformBuffer() {
        if (uniformBuffer == null) {
            uniformBuffer = RenderSystem.getDevice().createBuffer(() -> "Metallum Extra standard uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, IrisUniforms.layout().size());
        }
        return uniformBuffer;
    }

    /** This frame's value of a standard uniform, for the debug script and tests. */
    public static double @Nullable [] uniform(final String name) {
        return uniformValues.get(name);
    }

    // ---- depth ----

    /**
     * Copies the game's depth into {@code depthtex0} (with translucent terrain) or {@code depthtex1} (without it) as OpenGL depth:
     * a full-screen pass that converts each value, since the game's own depth runs the other way and cannot be used as it is.
     */
    private static void snapshotDepth(final RenderTarget world, final boolean withTranslucent) {
        TextureTarget target = TARGETS.depthTarget(withTranslucent);
        if (target == null) return;
        if (depthParams == null) {
            depthParams = RenderSystem.getDevice().createBuffer(() -> "Metallum Extra depth conversion", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        }
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
        data.putFloat(0, GAME_PROJECTION.m22()).putFloat(4, GAME_PROJECTION.m32()).putFloat(8, IrisUniformValues.NEAR).putFloat(12, far);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(depthParams.slice(), data);
        if (depthPipeline == null) {
            depthPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/internal/depth"))
                    .withVertexShader(BLIT_VERTEX)
                    .withFragmentShader(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "internal/depth"))
                    .withBindGroupLayout(BindGroupLayout.builder().withSampler("u_Depth").withUniform("DepthParams", UniformType.UNIFORM_BUFFER).build())
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_COLOR))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Metallum Extra depth")
                .withRenderArea(new RenderPass.RenderArea(0, 0, TARGETS.width(), TARGETS.height()))
                .withColorAttachment(target.getColorTextureView())
                .withDepthAttachment(target.getDepthTextureView());
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(depthPipeline);
            pass.setUniform("DepthParams", depthParams);
            pass.bindTexture("u_Depth", world.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        passes++;
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

    /** How many of the first pixels of the top row {@link #traceFloats} logs. */
    private static final int ROW_PIXELS = 64;

    /**
     * Development aid: logs the first pixels of the top row of a buffer of any format as numbers, which is how a test sees
     * values that do not fit in 8 bits. The row is copied to a buffer the CPU can read; the log line comes when the copy is done.
     */
    private static void traceFloats(final int number, final String program, final int index, final TextureTarget target, final GpuFormat format) {
        int width = target.width;
        int height = target.height;
        int pixel = IrisTargets.bytesPerPixel(format);
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Metallum Extra trace", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * pixel);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), buffer, 0, () -> {
            try (com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView view = buffer.map(true, false)) {
                java.nio.ByteBuffer data = view.data().order(java.nio.ByteOrder.nativeOrder());
                String name = format.name();
                int channels = name.startsWith("RGBA") ? 4 : name.startsWith("RGB") ? 3 : name.startsWith("RG") ? 2 : 1;
                int bytes = pixel / channels;
                StringBuilder text = new StringBuilder();
                for (int x = 0; x < Math.min(ROW_PIXELS, width); x++) {
                    text.append(" (");
                    for (int c = 0; c < 4; c++) {
                        double value = 0;
                        if (c < channels) {
                            int at = x * pixel + c * bytes;
                            value = name.contains("FLOAT") ? (bytes == 4 ? data.getFloat(at) : Float.float16ToFloat(data.getShort(at)))
                                    : bytes == 1 ? (data.get(at) & 255) / 255.0 : (data.getShort(at) & 65535) / 65535.0;
                        }
                        text.append(c > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.6f", value));
                    }
                    text.append(')');
                }
                MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} program {} colortex{} floats {}x{} row0{}", number, program, index, width, height, text);
            } finally {
                buffer.close();
            }
        }, 0);
    }

    /** A pack that breaks while it runs is put aside, and the one before it (or the built-in shaders) takes over. */
    private static void guarded(final Runnable body) {
        try {
            inStage = true;
            body.run();
        } catch (RuntimeException e) {
            handleFailure(e);
        } finally {
            inStage = false;
        }
    }

    private static void handleFailure(final RuntimeException e) {
        // A pipeline of the pack that has just been put aside: its frame is dropped, and the pack now in use is innocent.
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof StalePipelineException) {
                forget();
                return;
            }
        }
        if (!PackManager.fail(e)) throw e;
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
        for (String name : step.uniforms()) {
            double[] value = uniformValues.get(name);
            StringBuilder text = new StringBuilder();
            for (double component : value) text.append(String.format(java.util.Locale.ROOT, " %.6f", component));
            MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} program {} uniform {}{}", number, program.name(), name, text);
        }
        for (int index : step.writes()) {
            GpuFormat format = TARGETS.spec(index).format();
            if (format != GpuFormat.RGBA8_UNORM) {
                traceFloats(number, program.name(), index, TARGETS.readTarget(index), format);
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
