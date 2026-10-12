package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.CustomUniforms;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.metallumextra.shader.pack.IrisUniforms;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.metallumextra.shader.ComputeBinding;
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
    private static final Identifier UNPACK_FRAGMENT = Identifier.fromNamespaceAndPath(com.metallumextra.MetallumExtra.MOD_ID, "internal/unpack");
    private static final Map<Integer, RenderPipeline> UNPACKS = new HashMap<>();
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
    private static final org.joml.Matrix4f BASE_PROJECTION = new org.joml.Matrix4f();
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

    private static String profileLine = "";
    private static long profileAt;
    private static ShaderPack profilePack;

    /** The lines of the F3 screen: what runs, like Iris's own. */
    public static java.util.List<String> debugLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MetallumExtra.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        lines.add("[Metallum Extra] Version: " + version);
        if (com.metallumextra.ExtraConfig.get().effectiveUpscaleMode() != com.metallumextra.fx.UpscaleMode.OFF) lines.add("[Metallum Extra] Upscaling: " + com.metallumextra.fx.Upscale.status());
        IrisPlan current = plan;
        if (!inUse() || current == null) {
            lines.add("[Metallum Extra] Shaders: off");
            return lines;
        }
        ShaderPack pack = PackManager.active();
        lines.add("[Metallum Extra] Shaderpack: " + pack.name());
        long now = System.nanoTime();
        if (pack != profilePack || now - profileAt > 1_000_000_000L) {
            profilePack = pack;
            profileAt = now;
            try {
                var options = pack.options();
                var profiles = com.metallumextra.shader.pack.PackProfiles.of(ProgramSet.discover(pack, null));
                String name = profiles.isEmpty() ? null : com.metallumextra.shader.pack.PackProfiles.current(options, o -> com.metallumextra.shader.pack.PackOptions.get(pack.name(), o), profiles);
                int changed = 0;
                for (var option : options) if (com.metallumextra.shader.pack.PackOptions.get(pack.name(), option) != option.defaultIndex()) changed++;
                profileLine = "[Metallum Extra] Profile: " + (name == null ? "Custom" : name) + " (" + changed + " options changed by user)";
            } catch (RuntimeException e) {
                profileLine = "[Metallum Extra] Profile: ?";
            }
        }
        lines.add(profileLine);
        lines.add("[Metallum Extra] Passes: " + lastPasses + " (" + copies + " copies), " + TARGETS.textures() + " textures " + TARGETS.bytes() / (1024 * 1024) + " MB, " + current.stepCount() + " programs");
        lines.add("[Metallum Extra] Shadows: " + current.shadowResolution() + "px, terrain drawn " + ShadowPass.summary().replace("shadow terrain drawn ", ""));
        return lines;
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
                IrisPlan built;
                try (var step = com.metallumextra.ResourceLog.step("plan", pack.name() + "/" + dimension)) {
                    built = IrisPlan.build(pack, dimension);
                }
                customUniforms = built.programs().customUniforms(IrisPipeline::biomeConstant);
                itemIds = new HashMap<>();
                for (var rule : built.programs().itemMappings().rules()) itemIds.put(rule.namespace() + ":" + rule.name(), rule.id());
                entityIds = new HashMap<>();
                for (var rule : built.programs().entityMappings().rules()) entityIds.put(rule.namespace() + ":" + rule.name(), rule.id());
                // Blocks of chunks already built carry the ids of the old mapping: they are built again when it changes.
                if (BlockIds.use(built.programs().blockMappings())) Minecraft.getInstance().levelExtractor.allChanged();
                PIPELINES.clear();
                KERNELS.clear();
                setupRan = false;
                plan = built;
                generation++;
                com.metallum.render.MetallumExtraNative.storageTextures(built.hasCompute());
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
    public static RenderPassDescriptor attachWorldTargets(final RenderPassDescriptor descriptor) {
        if (inStage || !inUse() || plan == null || descriptor.colorAttachments().size() != 1) return descriptor;
        int phase = Shaders.phase();
        if (phase == Shaders.PHASE_SHADOW) {
            // The shadow map's second color attachment goes with every pass that draws into the first.
            TextureTarget map = TARGETS.shadow(), second = TARGETS.shadow1();
            if (map != null && second != null && descriptor.colorAttachments().get(0) != null
                    && descriptor.colorAttachments().get(0).textureView().texture() == map.getColorTexture()) {
                return withColorAttachments(descriptor, second.getColorTextureView());
            }
            return descriptor;
        }
        if (plan.worldBuffers().isEmpty()) return descriptor;
        if (phase != Shaders.PHASE_WORLD && phase != Shaders.PHASE_HAND) return descriptor;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (TARGETS.width() == 0 || !ready(main) || descriptor.colorAttachments().get(0) == null
                || descriptor.colorAttachments().get(0).textureView().texture() != main.getColorTexture()) return descriptor;
        // The buffers in the order of their attachments (see IrisPlan#attachment): the ones that stand alone, then one for each group packed together.
        java.util.List<GpuTextureView> extra = new java.util.ArrayList<>();
        for (int buffer : plan.worldBuffers()) {
            if (plan.packGroupOf(buffer) < 0) extra.add(TARGETS.read(buffer));
        }
        for (int group = 0; group < plan.packGroups().size(); group++) extra.add(TARGETS.packedView(group));
        return withColorAttachments(descriptor, extra.toArray(new GpuTextureView[0]));
    }

    /** The descriptor with more color attachments after its own (it is a record: a new one is made). */
    private static RenderPassDescriptor withColorAttachments(final RenderPassDescriptor descriptor, final GpuTextureView... views) {
        java.util.List<RenderPassDescriptor.Attachment<java.util.Optional<org.joml.Vector4fc>>> attachments = new java.util.ArrayList<>(descriptor.colorAttachments());
        for (GpuTextureView view : views) attachments.add(new RenderPassDescriptor.Attachment<>(view, java.util.Optional.empty()));
        return new RenderPassDescriptor(descriptor.label(), attachments, descriptor.depthAttachment(), descriptor.renderArea());
    }

    /**
     * Binds what slot {@code slot} of the programs that draw the world stands for: a picture of the pack, or a color buffer. A buffer that this very pass
     * is drawing into cannot also be read (Metal does not allow it), so the program is given an empty picture for it.
     */
    static void bindWorldSlot(final ShaderBindings.Binder pass, final String name, final int slot) {
        IrisPlan current = plan;
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        ShaderPack pack = PackManager.active();
        if (current != null && slot < current.worldSlots().size()) {
            IrisPlan.Input input = current.worldSlots().get(slot);
            if (input.colorBuffer() == IrisPlan.CUSTOM) {
                var view = customView(pack, input.texture());
                if (view != null) {
                    pass.bindTexture(name, view, PackTextures.sampler(pack, input.texture()));
                    return;
                }
            } else if (input.colorBuffer() >= 0 && TARGETS.hasBuffer(input.colorBuffer())) {
                boolean attached = current.worldBuffers().contains(input.colorBuffer());
                var view = attached ? PackTextures.view(pack, "mx:black") : TARGETS.read(input.colorBuffer());
                if (view != null) {
                    pass.bindTexture(name, view, linear);
                    return;
                }
            }
        }
        // Nothing to give it (the pack was put aside in this very frame, or the picture is not ready): an empty texel, since a sampler left unbound ends the game.
        TextureTarget empty = TARGETS.emptyShadow();
        if (empty != null) pass.bindTexture(name, empty.getColorTextureView(), linear);
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
    static com.mojang.renderpearl.api.textures.GpuTextureView shadowDepth() {
        TextureTarget shadow = Shaders.phase() == Shaders.PHASE_SHADOW ? null : TARGETS.shadow();
        TextureTarget use = shadow != null ? shadow : TARGETS.emptyShadow();
        return use != null ? use.getDepthTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    static com.mojang.renderpearl.api.textures.GpuTextureView shadowColor1() {
        TextureTarget shadow = Shaders.phase() == Shaders.PHASE_SHADOW ? null : TARGETS.shadow1();
        TextureTarget use = shadow != null ? shadow : TARGETS.emptyShadow();
        return use != null ? use.getColorTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    static com.mojang.renderpearl.api.textures.GpuTextureView shadowColor() {
        TextureTarget shadow = Shaders.phase() == Shaders.PHASE_SHADOW ? null : TARGETS.shadow();
        TextureTarget use = shadow != null ? shadow : TARGETS.emptyShadow();
        return use != null ? use.getColorTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    /** The world as it was before the translucent things (water, glass) were drawn: what their programs read as {@code colortex0}. */
    static com.mojang.renderpearl.api.textures.GpuTextureView worldColor() {
        return TARGETS.hasBuffer(0) ? TARGETS.read(0) : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
    }

    /** The depth of the world before the translucent things were drawn: {@code depthtex0} and {@code depthtex1} of their programs. */
    static com.mojang.renderpearl.api.textures.GpuTextureView worldDepth() {
        TextureTarget target = TARGETS.depthTarget(false);
        return target != null ? target.getDepthTextureView() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    /** The depth of the world without the held item ({@code depthtex2}) for the programs that draw the world. */
    static com.mojang.renderpearl.api.textures.GpuTextureView worldDepthWithoutHand() {
        TextureTarget target = TARGETS.depthWithoutHand();
        return target != null ? target.getDepthTextureView() : worldDepth();
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
        KERNELS.clear();
        BLITS.clear();
        UNPACKS.clear();
        TARGETS.release();
        STORAGE.values().forEach(GpuBuffer::close);
        STORAGE.clear();
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
        depthPipeline = null;
        PackTextures.release();
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
            PackTextures.prepare(PackManager.active(), current);
            frame++;
            lastPasses = passes;
            passes = 0;
            copies = 0;
            worldCopied = false;
            handDepth = null;
            TARGETS.clearForFrame(fog);
            captureUniforms(current, main);
            if (!setupRan) {
                // The compute programs that prepare things once, before the first frame the pack draws.
                setupRan = true;
                run(current.steps(ProgramSet.Stage.SETUP), main);
            }
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
            RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            run(again.steps(ProgramSet.Stage.SHADOWCOMP), mainTarget);
            run(again.steps(ProgramSet.Stage.PREPARE), mainTarget);
        });
    }

    /** Whether the plan's setup programs have run. */
    private static boolean setupRan;

    /** Before translucent terrain: what has been drawn so far, which is the opaque world. */
    static void beforeTranslucent() {
        guarded(() -> {
            IrisPlan current = plan();
            if (current == null || TARGETS.width() == 0 || !TARGETS.isFor(current)) return;
            List<IrisPlan.Step> deferred = current.steps(ProgramSet.Stage.DEFERRED);
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (!ready(main)) return;
            unpackBuffers(current);
            snapshotDepth(main, false);
            if (handDepth != null) overlayHandDepth(handDepth, false);
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

    /** Whether the world is drawn and its composite programs wait for the held item. */
    private static boolean compositePending;
    /** The depth the held item was drawn with, when it was drawn ahead of the translucent terrain; null until then in a frame. */
    private static @Nullable GpuTextureView handDepth;

    /** Whether the held item can be drawn now, with the world's pass closed and the pack's buffers ready (see {@link Shaders#beforeTranslucentTerrain}). */
    static boolean readyForHand() {
        IrisPlan current = plan();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return current != null && current.afterWorld() && ready(main) && TARGETS.isFor(current) && TARGETS.width() != 0;
    }

    /** The held item has been drawn with this depth. */
    static void handDrawn(final GpuTextureView depth) {
        handDepth = depth;
    }

    /**
     * The world is finished and the held item comes next: the world's depth is kept before the item's own pass clears it. The composite programs and the final one run
     * once the item is drawn, since they light and finish what the pack's programs wrote for it as for the rest.
     */
    static void afterWorld() {
        guarded(() -> {
            IrisPlan current = plan();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            compositePending = false;
            if (current == null || !current.afterWorld() || !ready(main) || !TARGETS.isFor(current)) return;
            snapshotDepth(main, true);
            // depthtex2 is the world before the held item goes into it, and depthtex0 is the world with it.
            if (current.usesDepth(2)) snapshotDepthInto(main, TARGETS.depthWithoutHand());
            if (handDepth != null) overlayHandDepth(handDepth, true);
            if (current.usesDepth(0)) copies++;
            compositePending = true;
        });
    }

    /** The held item is drawn (or there is none): composite programs, then the final one, which draws to the screen. */
    static void afterHand() {
        if (!compositePending) return;
        compositePending = false;
        guarded(() -> {
            IrisPlan current = plan();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (current == null || !current.afterWorld() || !ready(main) || !TARGETS.isFor(current)) return;
            // An item drawn after the rest has only the game's own depth buffer to say where it is.
            if (handDepth == null) overlayHandDepth(main.getDepthTextureView(), true);
            handDepth = null;
            unpackBuffers(current);
            // Translucent things and the held item were drawn into the game's image after the deferred programs: it, not buffer 0, is the finished world.
            worldIntoColor0(main);
            run(current.steps(ProgramSet.Stage.COMPOSITE), main);
            IrisPlan.Step last = current.finalStep();
            if (last != null) {
                draw(last, main);
            } else if (!current.steps(ProgramSet.Stage.COMPOSITE).isEmpty()) {
                // Without a final program the screen shows buffer 0.
                colorZeroIntoWorld(main);
            }
            if (DEBUG_BUFFER >= 0 && TARGETS.hasBuffer(DEBUG_BUFFER)) blit(TARGETS.read(DEBUG_BUFFER), main.getColorTextureView(), GpuFormat.RGBA8_UNORM);
            worldCopied = false;
            debugStopped = false;
            if (traceFrames > 0) traceFrames--;
        });
    }

    /** Development aid: {@code -Dmetallumextra.debugBuffer=N} shows buffer colortexN on the screen instead of the pack's final picture. */
    private static final int DEBUG_BUFFER = Integer.getInteger("metallumextra.debugBuffer", -1);

    private static boolean ready(final RenderTarget main) {
        return TARGETS.width() == Math.max(main.width, 1) && TARGETS.height() == Math.max(main.height, 1);
    }

    /** Runs a stage's programs: each draws, then the buffers it drew into are flipped. */
    private static void run(final List<IrisPlan.Step> steps, final RenderTarget main) {
        for (IrisPlan.Step step : steps) draw(step, main);
    }

    /** Development aid: {@code -Dmetallumextra.debugAfter=<program>} shows what that program drew, and no program after it runs. */
    private static final String DEBUG_AFTER = System.getProperty("metallumextra.debugAfter");
    private static boolean debugStopped;

    /** The compute programs made ready to run, by program name; a program that cannot be made ready is kept as a failure so it is not tried every frame. */
    private static final Map<String, Optional<Kernel>> KERNELS = new HashMap<>();

    private record Kernel(ComputeCompiler.Compiled compiled, com.metallum.render.MetallumExtraCompute.Kernel metal) {
    }

    private static @Nullable Kernel kernelFor(final IrisPlan.Step step) {
        return KERNELS.computeIfAbsent(step.program().name(), name -> {
            try (var timed = com.metallumextra.ResourceLog.step("compute-compile", name)) {
                String glsl = ShaderSources.computeSource(step.program(), step.uniforms());
                String dump = System.getProperty("metallumextra.dumpCompute");
                if (dump != null) {
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(dump, name + ".comp.glsl"), glsl);
                    } catch (java.io.IOException e) {
                        // A development aid only.
                    }
                }
                ComputeCompiler.Compiled compiled = ComputeCompiler.compile(name, glsl);
                if (dump != null) {
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(dump, name + ".metal"), compiled.metal());
                    } catch (java.io.IOException e) {
                        // A development aid only.
                    }
                }
                var metal = com.metallum.render.MetallumExtraCompute.create(name, compiled.metal(), compiled.entry());
                if (metal == null) throw new IllegalStateException("Metal would not make the compute program " + name);
                return Optional.of(new Kernel(compiled, metal));
            } catch (RuntimeException e) {
                MetallumExtra.LOGGER.error("[Metallum Extra] The compute program {} cannot be used: {}", name, e.getMessage());
                handleFailure(new IllegalStateException(e.getMessage(), e));
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Runs a compute program: its textures and images are the pack's buffers, and its threads cover what the program says. */
    private static void dispatch(final IrisPlan.Step step) {
        Kernel kernel = kernelFor(step);
        if (kernel == null) return;
        IrisPlan.Compute compute = step.compute();
        ShaderPack pack = PackManager.active();
        List<ComputeBinding> bindings = new java.util.ArrayList<>();
        for (ComputeCompiler.Resource resource : kernel.compiled.resources()) {
            switch (resource.kind()) {
                case BLOCK -> bindings.add(new ComputeBinding(resource, uniformBuffer().slice(), null, null, false));
                case TEXTURE -> {
                    IrisPlan.Input input = null;
                    for (IrisPlan.Input candidate : step.inputs()) if (candidate.name().equals(resource.name())) input = candidate;
                    if (input == null) throw new IllegalStateException(step.program().name() + " has no texture " + resource.name());
                    GpuTextureView view;
                    if (input.colorBuffer() >= 0) view = TARGETS.read(input.colorBuffer());
                    else if (input.colorBuffer() == IrisPlan.SHADOW_DEPTH) view = shadowDepth();
                    else if (input.colorBuffer() == IrisPlan.SHADOW_COLOR) view = shadowColor();
                    else if (input.colorBuffer() == IrisPlan.SHADOW_COLOR1) view = shadowColor1();
                    else if (input.colorBuffer() == IrisPlan.CUSTOM) view = java.util.Objects.requireNonNullElse(customView(pack, input.texture()), TARGETS.read(0));
                    else view = TARGETS.depth(input.depthTexture());
                    bindings.add(new ComputeBinding(resource, null, view, samplerOfKind(kindOf(pack, input)), false));
                }
                case IMAGE -> {
                    IrisPlan.Image image = null;
                    for (IrisPlan.Image candidate : compute.images()) if (candidate.name().equals(resource.name())) image = candidate;
                    if (image == null) throw new IllegalStateException(step.program().name() + " has no image " + resource.name());
                    GpuTextureView picture = image.buffer() >= 0 ? TARGETS.read(image.buffer()) : TARGETS.image(image.custom());
                    if (picture == null) throw new IllegalStateException("the image " + image.name() + " cannot be used: it is not a custom image of a format this version makes");
                    bindings.add(new ComputeBinding(resource, null, picture, null, image.writes()));
                }
                case BUFFER -> bindings.add(new ComputeBinding(resource, storageBuffer(step, resource).slice(), null, null, true));
            }
        }
        int[] groups;
        if (compute.groups() != null) {
            groups = compute.groups();
        } else {
            groups = new int[] {
                    Math.max(1, (int) Math.ceil(TARGETS.width() * compute.render()[0] / compute.local()[0])),
                    Math.max(1, (int) Math.ceil(TARGETS.height() * compute.render()[1] / compute.local()[1])), 1};
        }
        String failed = com.metallum.render.MetallumExtraCompute.dispatch(kernel.metal, bindings, groups, compute.local());
        if (failed != null) throw new IllegalStateException("The compute program " + step.program().name() + " could not be run: " + failed);
        passes++;
    }

    private static void draw(final IrisPlan.Step step, final RenderTarget main) {
        if (debugStopped && DEBUG_AFTER != null) return;
        if (step.compute() != null) {
            dispatch(step);
            return;
        }
        ProgramSet.Program program = step.program();
        boolean isFinal = program.stage() == ProgramSet.Stage.FINAL;
        RenderPipeline pipeline = PIPELINES.computeIfAbsent(program.name(), name -> build(step));

        int areaWidth = isFinal ? TARGETS.width() : TARGETS.widthOf(step.writes()[0]);
        int areaHeight = isFinal ? TARGETS.height() : TARGETS.heightOf(step.writes()[0]);
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Metallum Extra " + program.name())
                .withRenderArea(new RenderPass.RenderArea(0, 0, areaWidth, areaHeight));
        if (isFinal) {
            descriptor.withColorAttachment(main.getColorTextureView());
        } else {
            for (int i = 0; i < step.writes().length; i++) descriptor.withColorAttachment(TARGETS.write(step.writes()[i], step.inPlace()[i]));
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            ShaderPack pack = PackManager.active();
            for (IrisPlan.Input input : step.inputs()) {
                GpuSampler sampler = samplerOfKind(kindOf(pack, input));
                if (input.colorBuffer() >= 0) pass.setUniform(input.name(), TARGETS.read(input.colorBuffer()), sampler);
                else if (input.colorBuffer() == IrisPlan.SHADOW_DEPTH) pass.setUniform(input.name(), shadowDepth(), sampler);
                else if (input.colorBuffer() == IrisPlan.SHADOW_COLOR) pass.setUniform(input.name(), shadowColor(), sampler);
                else if (input.colorBuffer() == IrisPlan.SHADOW_COLOR1) pass.setUniform(input.name(), shadowColor1(), sampler);
                else if (input.colorBuffer() == IrisPlan.CUSTOM) {
                    var view = customView(pack, input.texture());
                    pass.setUniform(input.name(), view != null ? view : TARGETS.read(0), sampler);
                }
                else pass.setUniform(input.name(), TARGETS.depth(input.depthTexture()), sampler);
            }
            pass.draw(3, 1, 0, 0);
        }
        passes++;
        for (int i = 0; i < step.writes().length; i++) {
            if (!step.inPlace()[i]) TARGETS.flip(step.writes()[i]);
        }
        if (traceFrames > 0) trace(step);
        if (DEBUG_AFTER != null && program.name().equals(DEBUG_AFTER) && step.writes().length > 0) {
            blit(TARGETS.read(step.writes()[0]), main.getColorTextureView(), GpuFormat.RGBA8_UNORM);
            debugStopped = true;
        }
    }

    // ---- the samplers a program's textures are read with ----

    /** Kinds of sampler: filtering and wrapping (the buffers and the shadow's colour are filtered, depth is not; a picture of the pack says what it wants). */
    private static final int KIND_LINEAR_CLAMP = 0, KIND_NEAREST_CLAMP = 1, KIND_LINEAR_REPEAT = 2, KIND_NEAREST_REPEAT = 3;
    private static final java.util.regex.Pattern STANDARD_PIPELINE = java.util.regex.Pattern.compile(".*pipeline/standard/\\d+/(\\w+)");

    static {
        // A program of a pack that reads more textures than Metal has samplers shares them between the textures that are read the same way (see SamplerSlots).
        com.metallumextra.SamplerSlots.kinds(IrisPipeline::samplerKinds);
    }

    private static int kindOf(final ShaderPack pack, final IrisPlan.Input input) {
        // An integer buffer cannot be filtered (GL reads it with texelFetch or nearest only), so it is read nearest.
        if (input.colorBuffer() >= 0 && integerBuffer(input.colorBuffer())) return KIND_NEAREST_CLAMP;
        if (input.colorBuffer() >= 0 || input.colorBuffer() == IrisPlan.SHADOW_COLOR || input.colorBuffer() == IrisPlan.SHADOW_COLOR1) return KIND_LINEAR_CLAMP;
        if (input.colorBuffer() == IrisPlan.CUSTOM) return PackTextures.kind(pack, input.texture());
        return KIND_NEAREST_CLAMP;
    }

    /** The storage buffers made so far, by the binding their program gives them; they keep what the kernels wrote from frame to frame. */
    private static final java.util.Map<Integer, GpuBuffer> STORAGE = new java.util.HashMap<>();

    /**
     * The storage buffer a compute program declares as {@code layout(binding = n) buffer ...}: {@code bufferObject.n} in shaders.properties is its size in bytes.
     * It starts out zeroed, and is made again when the pack changes.
     */
    private static GpuBuffer storageBuffer(final IrisPlan.Step step, final ComputeCompiler.Resource resource) {
        GpuBuffer existing = STORAGE.get(resource.binding());
        if (existing != null) return existing;
        String size = plan == null ? null : plan.programs().property("bufferObject." + resource.binding());
        int bytes;
        try {
            bytes = size == null ? -1 : Integer.parseInt(size);
        } catch (NumberFormatException e) {
            bytes = -1;
        }
        if (bytes <= 0) throw new IllegalStateException(step.program().name() + " uses the buffer " + resource.name() + ", which shaders.properties does not size (bufferObject." + resource.binding() + " = bytes)");
        GpuBuffer made = RenderSystem.getDevice().createBuffer(() -> "Metallum Extra storage " + resource.binding(), GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, bytes);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(made.slice(), java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder()));
        STORAGE.put(resource.binding(), made);
        return made;
    }

    /** The picture a custom input reads: one of the pack's pictures, or a custom image the pack declares ({@link ProgramSet#IMAGE_PATH}). */
    private static @Nullable GpuTextureView customView(final ShaderPack pack, final String path) {
        if (path.startsWith(ProgramSet.IMAGE_PATH)) return TARGETS.image(path.substring(ProgramSet.IMAGE_PATH.length()));
        return PackTextures.view(pack, path);
    }

    /** Whether buffer {@code buffer} of the plan in use has an integer format (its programs read it as a usampler or isampler). */
    private static boolean integerBuffer(final int buffer) {
        IrisPlan current = plan;
        IrisPlan.Buffer spec = current == null ? null : current.buffers().get(buffer);
        if (spec == null) return false;
        String name = spec.format().name();
        return name.endsWith("_UINT") || name.endsWith("_SINT");
    }

    private static GpuSampler samplerOfKind(final int kind) {
        FilterMode filter = kind == KIND_LINEAR_CLAMP || kind == KIND_LINEAR_REPEAT ? FilterMode.LINEAR : FilterMode.NEAREST;
        return kind == KIND_LINEAR_REPEAT || kind == KIND_NEAREST_REPEAT ? RenderSystem.getSamplerCache().getRepeat(filter) : RenderSystem.getSamplerCache().getClampToEdge(filter);
    }

    /** The kind of sampler each texture of a program's pipeline is read with, by the texture's name; null for a pipeline that is not one of a pack's programs. */
    private static java.util.@Nullable Map<String, Integer> samplerKinds(final String pipeline) {
        java.util.regex.Matcher match = STANDARD_PIPELINE.matcher(pipeline);
        IrisPlan current = plan;
        ShaderPack pack = PackManager.active();
        if (!match.matches() || current == null || pack == null) return null;
        IrisPlan.Step step = current.step(match.group(1));
        if (step == null) return null;
        Map<String, Integer> kinds = new HashMap<>();
        for (IrisPlan.Input input : step.inputs()) kinds.put(input.name(), kindOf(pack, input));
        return kinds;
    }

    private static RenderPipeline build(final IrisPlan.Step step) {
        ProgramSet.Program program = step.program();
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (IrisPlan.Input input : step.inputs()) layout.withUniform(input.name(), UniformType.COMBINED_IMAGE_SAMPLER);
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
        BASE_PROJECTION.set(camera.projectionMatrix);
        org.joml.Vector3fc skyColor = sky.skyColor != null ? sky.skyColor : new org.joml.Vector3f();
        var player = minecraft.player;
        var inputs = new IrisUniformValues.Inputs(new org.joml.Matrix4f(Shaders.globals().view), new org.joml.Matrix4f(GAME_PROJECTION), new org.joml.Matrix4f(BASE_PROJECTION), far,
                camera.pos.x, camera.pos.y, camera.pos.z, sky.sunAngle, sky.moonAngle, Shaders.sunPathTilt(),
                level.getOverworldClockTime(), sky.moonPhase.index(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F),
                FROZEN_TIME ? 0.0 : (now - START) / 1.0e9, frameSeconds, frameCounter++, main.width, main.height,
                (float) (double) minecraft.options.gamma().get(), lightmap.nightVisionEffectIntensity,
                player != null && player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) ? 1.0F : 0.0F, lightmap.darknessEffectScale,
                camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z,
                skyColor.x(), skyColor.y(), skyColor.z(),
                level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, camera.blockPos), level.getBrightness(net.minecraft.world.level.LightLayer.SKY, camera.blockPos),
                switch (camera.fogType) {
                    case WATER -> 1;
                    case LAVA -> 2;
                    case POWDER_SNOW -> 3;
                    default -> 0;
                });
        uniformValues = new HashMap<>(UNIFORMS.frame(inputs));
        var fogData = camera.fogData;
        // The fog that is nearer: the one the camera is inside of (water, lava, blindness) or the one the render distance makes.
        boolean environmental = fogData.environmentalEnd > 0.0F && fogData.environmentalEnd < fogData.renderDistanceEnd;
        uniformValues.put("fogStart", new double[] {environmental ? fogData.environmentalStart : fogData.renderDistanceStart});
        uniformValues.put("fogEnd", new double[] {environmental ? fogData.environmentalEnd : fogData.renderDistanceEnd});
        addBiome(level, camera.blockPos, uniformValues);
        addHeldItems(player, uniformValues);
        addEnvironment(minecraft, level, camera, player, uniformValues);
        uniformValues.put("MxBlockIds", BlockIds.table());
        // What nothing supplies is zero: no held item, no entity being drawn, and so on.
        for (IrisUniforms.Uniform uniform : IrisUniforms.allUniforms()) {
            uniformValues.computeIfAbsent(uniform.name(), name -> new double[valueCount(uniform.type())]);
        }
        if (customUniforms != null) customUniforms.evaluate(uniformValues, frameSeconds);
        addShadowAndExtensions(uniformValues, false);
        if (traceFrames > 0) {
            MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} game projection: m00 {} m11 {} m22 {} m32 {} m23 {} m33 {} m20 {} m21 {} far {}", frame, GAME_PROJECTION.m00(), GAME_PROJECTION.m11(),
                    GAME_PROJECTION.m22(), GAME_PROJECTION.m32(), GAME_PROJECTION.m23(), GAME_PROJECTION.m33(), GAME_PROJECTION.m20(), GAME_PROJECTION.m21(), far);
        }
        writeUniforms();
    }

    private static double centerDepth = 1.0;

    /** The uniforms about the world, the player and the clock that the game answers directly (see the list in {@link IrisUniforms}). */
    private static void addEnvironment(final Minecraft minecraft, final net.minecraft.client.multiplayer.ClientLevel level, final net.minecraft.client.renderer.state.level.CameraRenderState camera,
                                       final net.minecraft.world.entity.player.@Nullable Player player, final Map<String, double[]> values) {
        values.put("eyePosition", new double[] {camera.pos.x, camera.pos.y, camera.pos.z});
        if (player != null) {
            var look = player.getLookAngle();
            values.put("playerLookVector", new double[] {look.x, look.y, look.z});
            var body = net.minecraft.world.phys.Vec3.directionFromRotation(0.0F, player.yBodyRot);
            values.put("playerBodyVector", new double[] {body.x, body.y, body.z});
            values.put("isSpectator", new double[] {player.isSpectator() ? 1 : 0});
        }
        values.put("hideGUI", new double[] {minecraft.gui.hud.isHidden() ? 1 : 0});
        values.put("firstPersonCamera", new double[] {minecraft.options.getCameraType().isFirstPerson() ? 1 : 0});
        var type = level.dimensionType();
        values.put("hasCeiling", new double[] {type.hasCeiling() ? 1 : 0});
        values.put("hasSkylight", new double[] {type.hasSkyLight() ? 1 : 0});
        values.put("ambientLight", new double[] {type.ambientLight()});
        values.put("bedrockLevel", new double[] {level.getMinY()});
        values.put("heightLimit", new double[] {level.getHeight()});
        values.put("logicalHeightLimit", new double[] {type.logicalHeight()});
        values.put("cloudHeight", new double[] {type.hasCeiling() || !type.hasSkyLight() ? 0.0 : 192.33});
        boolean fogged = camera.fogData.renderDistanceEnd > 0 || camera.fogData.environmentalEnd > 0;
        values.put("fogMode", new double[] {fogged ? 9729 : 0});
        values.put("fogDensity", new double[] {0.1});
        values.put("temperature", new double[] {level.getBiome(camera.blockPos).value().getBaseTemperature()});
        var now = java.time.LocalDateTime.now();
        values.put("currentDate", new double[] {now.getYear(), now.getMonthValue(), now.getDayOfMonth()});
        values.put("currentTime", new double[] {now.getHour(), now.getMinute(), now.getSecond()});
        var yearStart = java.time.LocalDateTime.of(now.getYear(), 1, 1, 0, 0);
        values.put("currentYearTime", new double[] {java.time.Duration.between(yearStart, now).getSeconds(), java.time.Duration.between(yearStart, yearStart.plusYears(1)).getSeconds()});
        // Where the cross hairs point, as a depth: the pack's focus for depth of field. The game is asked rather than the depth buffer, which would stall.
        double distance = far;
        if (minecraft.getCameraEntity() != null) {
            var hit = minecraft.getCameraEntity().pick(far, 1.0F, false);
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) distance = Math.min(far, hit.getLocation().distanceTo(camera.pos));
        }
        double near = 0.05, farPlane = far;
        double target = 0.5 * ((farPlane + near) / (farPlane - near) - 2.0 * farPlane * near / ((farPlane - near) * Math.max(distance, near))) + 0.5;
        centerDepth += (target - centerDepth) * (1.0 - Math.exp(-frameSeconds / 0.5));
        values.put("centerDepthSmooth", new double[] {centerDepth});
    }

    /** What the pack's {@code item.properties} calls each item: its registry name to its id. */
    private static Map<String, Integer> itemIds = new HashMap<>();
    /** The ids of entities in the pack's entity.properties, by their registry key ({@code minecraft:zombie}). */
    private static Map<String, Integer> entityIds = new HashMap<>();

    /** The id the pack gives the entity a render state is for, or 0 for none: what {@code entityId} is for the entity's draws. */
    public static int entityIdOf(final Object state) {
        if (!(state instanceof net.minecraft.client.renderer.entity.state.EntityRenderState render) || render.entityType == null || entityIds.isEmpty()) return 0;
        Integer id = entityIds.get(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(render.entityType).toString());
        return id == null ? 0 : id;
    }

    /**
     * {@code heldItemId} and {@code heldItemId2} (main hand and off hand: the id {@code item.properties} gave the item, 0 for an empty hand and -1 for an item
     * it does not name) and {@code heldBlockLightValue}/{@code 2} (how brightly the block the hand holds shines: a torch is 14).
     */
    private static void addHeldItems(final net.minecraft.world.entity.player.@Nullable Player player, final Map<String, double[]> values) {
        net.minecraft.world.item.ItemStack main = player == null ? net.minecraft.world.item.ItemStack.EMPTY : player.getMainHandItem();
        net.minecraft.world.item.ItemStack off = player == null ? net.minecraft.world.item.ItemStack.EMPTY : player.getOffhandItem();
        values.put("heldItemId", new double[] {heldId(main)});
        values.put("heldItemId2", new double[] {heldId(off)});
        values.put("heldBlockLightValue", new double[] {heldLight(main)});
        values.put("heldBlockLightValue2", new double[] {heldLight(off)});
    }

    private static int heldId(final net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Integer id = itemIds.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        return id == null ? -1 : id;
    }

    private static int heldLight(final net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem block ? block.getBlock().defaultBlockState().getLightEmission() : 0;
    }

    /** The custom uniforms of the pack in use; null until the plan has made them. */
    private static @Nullable CustomUniforms customUniforms;

    /** What a biome's name stands for in the pack's expressions ({@code BIOME_PLAINS}): its number in the game's registry, or -1 when there is no such biome. */
    private static @Nullable Double biomeConstant(final String name) {
        if (!name.startsWith("BIOME_")) return null;
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var holder = registry.get(net.minecraft.resources.Identifier.withDefaultNamespace(name.substring("BIOME_".length()).toLowerCase(java.util.Locale.ROOT)));
        return holder.isPresent() ? (double) registry.getId(holder.get().value()) : -1.0;
    }

    /** {@code biome} (its number, as {@link #biomeConstant}) and {@code biome_precipitation} (0 none, 1 rain, 2 snow) where the camera is. */
    private static void addBiome(final net.minecraft.client.multiplayer.ClientLevel level, final net.minecraft.core.BlockPos pos, final Map<String, double[]> values) {
        var biome = level.getBiome(pos);
        var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        values.put("biome", new double[] {registry.getId(biome.value())});
        var precipitation = biome.value().getPrecipitationAt(pos, level.getSeaLevel());
        values.put("biome_precipitation", new double[] {precipitation == net.minecraft.world.level.biome.Biome.Precipitation.RAIN ? 1 : precipitation == net.minecraft.world.level.biome.Biome.Precipitation.SNOW ? 2 : 0});
    }

    /** How many numbers a value of this GLSL type has. */
    private static int valueCount(final String type) {
        return switch (type) {
            case "float", "int" -> 1;
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            case "mat3" -> 9;
            case "mat4" -> 16;
            case "ivec4[128]" -> 512;
            default -> 1;
        };
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
        if (uniformBuffer != null && uniformBuffer.size() != IrisUniforms.layout().size()) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
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
        snapshotDepthInto(world, TARGETS.depthTarget(withTranslucent));
    }

    private static void snapshotDepthInto(final RenderTarget world, final @Nullable TextureTarget target) {
        if (target == null) return;
        if (depthParams == null) {
            depthParams = RenderSystem.getDevice().createBuffer(() -> "Metallum Extra depth conversion", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        }
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
        data.putFloat(0, BASE_PROJECTION.m22()).putFloat(4, BASE_PROJECTION.m32()).putFloat(8, IrisUniformValues.NEAR).putFloat(12, far);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(depthParams.slice(), data);
        if (depthPipeline == null) {
            depthPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/internal/depth"))
                    .withVertexShader(BLIT_VERTEX)
                    .withFragmentShader(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "internal/depth"))
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("u_Depth", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("DepthParams", UniformType.UNIFORM_BUFFER).build())
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_COLOR))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Metallum Extra depth")
                .withRenderArea(new RenderPass.RenderArea(0, 0, TARGETS.width(), TARGETS.height()))
                .withColorAttachment(target.getColorTextureView())
                .withDepthAttachment(target.getDepthTextureView())
                .build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(depthPipeline));
            pass.setUniform("DepthParams", depthParams);
            pass.setUniform("u_Depth", world.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        passes++;
    }

    private static @Nullable RenderPipeline handDepthPipeline;

    /**
     * Writes the held item's depth into {@code depthtex0}, over the world's: where the item was drawn, the game's depth buffer (cleared for it) has its depth,
     * which is turned into the squeezed depth a pack reads as a hand (see {@code depth_hand.fsh}).
     */
    private static void overlayHandDepth(final GpuTextureView handDepthView, final boolean withTranslucent) {
        TextureTarget target = TARGETS.depthTarget(withTranslucent);
        if (target == null || depthParams == null) return;
        if (handDepthPipeline == null) {
            handDepthPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/internal/depth_hand"))
                    .withVertexShader(BLIT_VERTEX)
                    .withFragmentShader(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "internal/depth_hand"))
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform("u_Depth", UniformType.COMBINED_IMAGE_SAMPLER).withUniform("DepthParams", UniformType.UNIFORM_BUFFER).build())
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_COLOR))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Metallum Extra hand depth")
                .withRenderArea(new RenderPass.RenderArea(0, 0, TARGETS.width(), TARGETS.height()))
                .withColorAttachment(target.getColorTextureView())
                .withDepthAttachment(target.getDepthTextureView())
                .build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(handDepthPipeline));
            pass.setUniform("DepthParams", depthParams);
            pass.setUniform("u_Depth", handDepthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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

    /**
     * The buffers the world was drawn into packed together (see {@link IrisPlan#packGroups}) are taken apart into the buffers themselves, which the programs after the
     * world read. Done before the deferred programs and again before the composite ones, since translucent things and the held item are drawn in between.
     */
    private static void unpackBuffers(final IrisPlan current) {
        for (int group = 0; group < current.packGroups().size(); group++) {
            java.util.List<Integer> buffers = current.packGroups().get(group);
            int count = buffers.size();
            RenderPipeline pipeline = UNPACKS.computeIfAbsent(count, c -> {
                RenderPipeline.Builder builder = RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/internal/unpack_" + c))
                        .withVertexShader(BLIT_VERTEX)
                        .withFragmentShader(UNPACK_FRAGMENT)
                        .withShaderDefine("COUNT", c)
                        .withBindGroupLayout(BindGroupLayout.builder().withUniform("packedBuffers", UniformType.COMBINED_IMAGE_SAMPLER).build())
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
                for (int i = 0; i < c; i++) builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
                return builder.build();
            });
            RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Metallum Extra unpack buffers")
                    .withRenderArea(new RenderPass.RenderArea(0, 0, TARGETS.width(), TARGETS.height()));
            for (int buffer : buffers) descriptor.withColorAttachment(TARGETS.read(buffer));
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                pass.setUniform("packedBuffers", TARGETS.packedView(group), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            passes++;
        }
    }

    /** Draws one image into another of a different format, which a plain copy cannot do. */
    private static void blit(final com.mojang.renderpearl.api.textures.GpuTextureView from, final com.mojang.renderpearl.api.textures.GpuTextureView to, final GpuFormat format) {
        RenderPipeline pipeline = BLITS.computeIfAbsent(format, f -> RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/internal/blit_" + f.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(BLIT_VERTEX)
                .withFragmentShader(BLIT_FRAGMENT)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("colortex0", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build());
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Metallum Extra blit", to, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform("colortex0", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        passes++;
    }

    // ---- failure and tracing ----

    /** How many of the first pixels of the top row {@link #traceFloats} logs. */
    private static final int ROW_PIXELS = 128;

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
            try (com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView view = buffer.map(true, false)) {
                java.nio.ByteBuffer data = view.data().order(java.nio.ByteOrder.nativeOrder());
                String name = format.name();
                int channels = name.startsWith("RGBA") ? 4 : name.startsWith("RGB") ? 3 : name.startsWith("RG") ? 2 : 1;
                int bytes = pixel / channels;
                StringBuilder text = new StringBuilder();
                // A row near the bottom, which is mostly ground, and a step along it that spreads the pixels over the screen.
                int rowAt = (height * 3 / 4) * width * pixel;
                int step = Math.max(1, width / ROW_PIXELS);
                for (int x = 0; x < width && text.length() < 12000; x += step) {
                    text.append(" (");
                    if (format == GpuFormat.RG11B10_FLOAT) {
                        int packed = data.getInt(rowAt + x * pixel);
                        text.append(String.format(java.util.Locale.ROOT, "%.6f,%.6f,%.6f,1.0)", unsignedFloat(packed & 0x7FF, 6), unsignedFloat((packed >>> 11) & 0x7FF, 6), unsignedFloat((packed >>> 22) & 0x3FF, 5)));
                        continue;
                    }
                    for (int c = 0; c < 4; c++) {
                        double value = 0;
                        if (c < channels) {
                            int at = rowAt + x * pixel + c * bytes;
                            value = name.contains("FLOAT") ? (bytes == 4 ? data.getFloat(at) : Float.float16ToFloat(data.getShort(at)))
                                    : bytes == 1 ? (data.get(at) & 255) / 255.0 : (data.getShort(at) & 65535) / 65535.0;
                        }
                        text.append(c > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.6f", value));
                    }
                    text.append(')');
                }
                MetallumExtra.LOGGER.info("[Metallum Extra] iris frame {} program {} colortex{} floats {}x{} {} {}B row3/4{}", number, program, index, width, height, format, pixel, text);
            } finally {
                buffer.close();
            }
        }, 0);
    }

    /** A float without a sign, in {@code mantissaBits} bits of mantissa after a five bit exponent (the channels of RG11B10). */
    private static double unsignedFloat(final int bits, final int mantissaBits) {
        int exponent = bits >> mantissaBits;
        double mantissa = (bits & ((1 << mantissaBits) - 1)) / (double) (1 << mantissaBits);
        if (exponent == 0) return mantissa * Math.pow(2.0, -14);
        if (exponent == 31) return mantissa == 0.0 ? Double.POSITIVE_INFINITY : Double.NaN;
        return (1.0 + mantissa) * Math.pow(2.0, exponent - 15);
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
