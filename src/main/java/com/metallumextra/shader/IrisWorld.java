package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.IrisPlan;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The game's own pipelines (mobs, items, block entities, particles, weather ...) drawn with a standard pack's programs.
 * <p>
 * While the world is drawn, each pipeline the game sets is swapped for a copy of it, made once and kept: with the shaders of the
 * pack's program for that kind of thing (see {@link IrisPlan.Use}) and with the color targets every pass that draws the world has
 * (see {@link IrisPipeline#attachWorldTargets}). Outside the world (menus, the inventory) the game's pipelines are used as they are,
 * so what the pack does not draw is not touched.
 * <p>
 * Which kind of thing a pipeline draws is read from the pipeline: the id of its shader and its name. The game's pipelines for entities
 * serve block entities too (a chest is drawn like a mob), so the pack's {@code gbuffers_block} program draws only the blocks the game
 * draws one at a time (falling sand, pistons), and its {@code gbuffers_entities} the rest.
 */
public final class IrisWorld {
    private static final boolean LOG = Boolean.getBoolean("metallumextra.logPipelines");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** The copies, by the pipeline they stand in for. A pipeline with nothing to change maps to itself. */
    private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> COPIES = new ConcurrentHashMap<>();
    /** The same for the held item and arm, which have programs of their own. */
    private static final Map<RenderPipeline, RenderPipeline> HAND_VARIANTS = new ConcurrentHashMap<>();
    /** The copies that draw into the shadow map, by the pipeline they stand in for; and the set of them, for telling which draws to keep. */
    private static final Map<RenderPipeline, RenderPipeline> SHADOW_VARIANTS = new ConcurrentHashMap<>();
    private static final Set<RenderPipeline> SHADOW_SET = ConcurrentHashMap.newKeySet();
    /** The pack's program and the pipeline's format behind each shader id handed out, for {@link ShaderSources}. */
    private static final Map<String, Entry> IDS = new ConcurrentHashMap<>();
    /** The game's own ids for each id handed out, kept for good: a pipeline made for a pack that has gone compiles as the game's own. */
    private static final Map<String, Identifier[]> ORIGINALS = new ConcurrentHashMap<>();
    private static int substituted;
    private static int passedThrough;

    /** What a shader id handed out stands for. */
    public record Entry(int generation, IrisPlan.Step step, IrisWorldAdapter.Format format, Kind kind, int stage) {
        public Entry(final int generation, final IrisPlan.Step step, final IrisWorldAdapter.Format format, final Kind kind) {
            this(generation, step, format, kind, 0);
        }
    }

    /** What the game is drawing on the sky right now: {@value #STAGE_SUN}, {@value #STAGE_MOON}, or 0 (see the pack's {@code renderStage}). */
    public static volatile int skyStage;
    public static final int STAGE_SUN = 4;
    public static final int STAGE_MOON = 5;
    private static final Map<RenderPipeline, Map<Integer, RenderPipeline>> SKY_VARIANTS = new java.util.HashMap<>();

    /** What a program is made to draw: the world, the shadow map, or the sky behind everything. */
    public enum Kind {
        WORLD, SHADOW, SKY
    }

    private IrisWorld() {
    }

    /** The vertex shader of a pipeline. */
    static Identifier vertexOf(final RenderPipeline pipeline) {
        return pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX);
    }

    /** The fragment shader of a pipeline. */
    static Identifier fragmentOf(final RenderPipeline pipeline) {
        return pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT);
    }

    /** Called when the plan changes or the pack is put away: the copies were made for the old one. */
    public static void forget() {
        VARIANTS.clear();
        SKY_VARIANTS.clear();
        COPIES.clear();
        HAND_VARIANTS.clear();
        GAME_VARIANTS.clear();
        sky = null;
        SHADOW_VARIANTS.clear();
        SHADOW_SET.clear();
        IDS.clear();
    }

    /** Whether the pipeline is one of the copies that draws into the shadow map. */
    public static boolean isShadowVariant(final RenderPipeline pipeline) {
        return SHADOW_SET.contains(pipeline);
    }

    public static String summary() {
        return "world pipelines: " + substituted + " copies made, " + passedThrough + " left alone";
    }

    /** The pipeline to draw with in place of {@code original}: itself, or its copy for the pack in use. */
    public static RenderPipeline pipelineFor(final RenderPipeline given) {
        // A copy this class made, handed in again (the pass was told twice, or the pack failed and the draw is retried): start from the game's.
        RenderPipeline original = COPIES.getOrDefault(given, given);
        if (!IrisPipeline.inUse()) return original;
        int phase = Shaders.phase();
        if (phase == Shaders.PHASE_SHADOW) return shadowPipelineFor(original);
        if (phase != Shaders.PHASE_WORLD && phase != Shaders.PHASE_HAND) return original;
        if (IrisPipeline.inStage()) return original;
        boolean hand = phase == Shaders.PHASE_HAND;
        if (!hand && skyStage != 0 && classify(original) == IrisPlan.Use.SKY_TEXTURED) {
            IrisPlan skyPlan = IrisPipeline.currentPlan();
            if (skyPlan == null) return original;
            int stage = skyStage;
            RenderPipeline skyMade = SKY_VARIANTS.computeIfAbsent(original, k -> new java.util.HashMap<>()).computeIfAbsent(stage, k -> make(original, skyPlan, false, stage));
            COPIES.put(skyMade, original);
            return skyMade;
        }
        Map<RenderPipeline, RenderPipeline> variants = hand ? HAND_VARIANTS : VARIANTS;
        RenderPipeline known = variants.get(original);
        if (known != null) return known;
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null) return original;
        RenderPipeline made = make(original, plan, hand, 0);
        variants.put(original, made);
        COPIES.put(made, original);
        return made;
    }

    private static final Map<RenderPipeline, RenderPipeline> GAME_VARIANTS = new ConcurrentHashMap<>();

    /**
     * The game's own shaders with the pack's buffers declared (none written), for a pass that already has those buffers attached when the pack's program for
     * the pipeline turns out not to compile: the pass cannot be given one that declares fewer, and the pack is put aside after this frame.
     */
    public static RenderPipeline withoutPack(final RenderPipeline origin) {
        IrisPlan plan = IrisPipeline.currentPlan();
        java.util.List<ColorTargetState> targets = origin.getColorTargetStates();
        if (plan == null || plan.worldBuffers().isEmpty() || targets.isEmpty() || targets.get(0) == null) return origin;
        return GAME_VARIANTS.computeIfAbsent(origin, key -> {
            String location = key.getLocation().getNamespace() + "/" + key.getLocation().getPath();
            RenderPipeline.Builder builder = copy(key, vertexOf(key), fragmentOf(key), "pipeline/world_game/" + IrisPipeline.generation() + "/" + location);
            builder.withColorTargetState(0, targets.get(0));
            declareExtras(builder, plan, null);
            return builder.build();
        });
    }

    /** The copy of a model pipeline that draws it with the pack's shadow program; the pipeline itself when it casts no shadow (the draw is then dropped). */
    private static RenderPipeline shadowPipelineFor(final RenderPipeline original) {
        if (IrisPipeline.inStage()) return original;
        RenderPipeline known = SHADOW_VARIANTS.get(original);
        if (known != null) return known;
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null) return original;
        RenderPipeline made = makeShadow(original, plan);
        if (made == original && plan.hasShadowColor1()) {
            // The pass has the shadow map's second color target, and the game refuses a pipeline that declares fewer. This pipeline casts no shadow (its draws
            // are dropped, see ShaderBindings#skipsDraws), so it only has to be accepted: a copy with the two targets, neither written.
            made = nonCasting(original);
            COPIES.put(made, original);
            SHADOW_VARIANTS.put(original, made);
            return made;
        }
        SHADOW_VARIANTS.put(original, made);
        if (made != original) {
            SHADOW_SET.add(made);
            COPIES.put(made, original);
        }
        return made;
    }

    private static RenderPipeline nonCasting(final RenderPipeline original) {
        String location = original.getLocation().getNamespace() + "/" + original.getLocation().getPath();
        RenderPipeline.Builder builder = copy(original, vertexOf(original), fragmentOf(original), "pipeline/shadow_skipped/" + IrisPipeline.generation() + "/" + location);
        java.util.List<ColorTargetState> targets = original.getColorTargetStates();
        if (!targets.isEmpty() && targets.get(0) != null) builder.withColorTargetState(0, new ColorTargetState(Optional.empty(), targets.get(0).format(), ColorTargetState.WRITE_NONE));
        else builder.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE));
        builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE));
        return builder.build();
    }

    private static RenderPipeline makeShadow(final RenderPipeline original, final IrisPlan plan) {
        String vertexPath = vertexOf(original).getPath();
        IrisPlan.Use use = vertexPath.equals("core/block") ? IrisPlan.Use.SHADOW_BLOCK
                : vertexPath.equals("core/entity") || vertexPath.equals("core/item") ? IrisPlan.Use.SHADOW_ENTITY : null;
        IrisPlan.Step step = use == null ? null : plan.worldStep(use);
        java.util.List<ColorTargetState> targets = original.getColorTargetStates();
        if (step == null || targets.isEmpty() || targets.get(0) == null || targets.get(0).format() != GpuFormat.RGBA8_UNORM) return original;
        IrisWorldAdapter.Format format = IrisWorldAdapter.Format.of(original);
        for (IrisPlan.Input input : step.inputs()) {
            if (input.name().equals("gtexture") && !format.atlas() || input.name().equals("lightmap") && !format.light()) return original;
        }
        String location = original.getLocation().getNamespace() + "/" + original.getLocation().getPath();
        String path = "iris_" + IrisPipeline.generation() + "_" + step.program().name() + "_shadow_" + location.replace('/', '_').replace(':', '_');
        IDS.put(path, new Entry(IrisPipeline.generation(), step, format, Kind.SHADOW));
        ORIGINALS.put(path, new Identifier[] {vertexOf(original), fragmentOf(original)});
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
        RenderPipeline.Builder builder = copy(original, id, id, "pipeline/shadow/" + IrisPipeline.generation() + "/" + location);
        // OpenGL depth: the map is cleared to 1 and the nearest to the light wins.
        builder.withDepthStencilState(Optional.of(new com.mojang.renderpearl.api.pipeline.DepthStencilState(com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN_OR_EQUAL, true)));
        builder.withColorTargetState(0, targets.get(0));
        declareShadowExtras(builder, plan, step);
        return builder.build();
    }

    /** The shadow map's second color target, when it has one: written only if the pack's shadow program writes it. */
    public static void declareShadowExtras(final RenderPipeline.Builder builder, final IrisPlan plan, final IrisPlan.@Nullable Step step) {
        if (!plan.hasShadowColor1()) return;
        boolean writes = false;
        if (step != null) {
            for (int written : step.writes()) writes |= written == 1;
        }
        builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, writes ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE));
    }

    /** The game's own id for a shader id this class handed out; null for any other. */
    public static @Nullable Identifier originalOf(final Identifier id, final boolean vertex) {
        Identifier[] pair = id.getNamespace().equals("minecraft") && id.getPath().startsWith("iris_") ? ORIGINALS.get(id.getPath()) : null;
        return pair == null ? null : pair[vertex ? 0 : 1];
    }

    public static @Nullable Entry entry(final Identifier id) {
        return id.getNamespace().equals("minecraft") ? IDS.get(id.getPath()) : null;
    }

    /** The kind of thing a pipeline draws, or null for one this version leaves to the game's own shader (sky, text, lines ...). */
    public static IrisPlan.@Nullable Use classify(final RenderPipeline pipeline) {
        String shader = vertexOf(pipeline).getPath();
        String name = pipeline.getLocation().getPath();
        boolean translucent = name.contains("translucent");
        // Portals (nether, end, gateway) are translucent textured quads: shade them as translucent entities so the
        // pack's fog applies. Pipelines without the samplers the program needs still pass through in make().
        if (shader.contains("portal") || shader.contains("gateway") || name.contains("portal") || name.contains("gateway")) {
            return IrisPlan.Use.ENTITY_TRANSLUCENT;
        }
        switch (shader) {
            case "core/entity":
                // The glowing ones (eyes) have programs of their own.
                if (pipeline.getShaderDefines().flags().contains("EMISSIVE")) return IrisPlan.Use.EYES;
                return translucent ? IrisPlan.Use.ENTITY_TRANSLUCENT : IrisPlan.Use.ENTITY;
            case "core/item":
                return translucent ? IrisPlan.Use.ENTITY_TRANSLUCENT : IrisPlan.Use.ENTITY;
            case "core/block":
                return translucent ? IrisPlan.Use.BLOCK_TRANSLUCENT : IrisPlan.Use.BLOCK;
            case "core/particle":
                if (name.contains("weather")) return IrisPlan.Use.WEATHER;
                return translucent ? IrisPlan.Use.PARTICLES_TRANSLUCENT : IrisPlan.Use.PARTICLES;
            case "core/rendertype_clouds":
                return IrisPlan.Use.CLOUDS;
            case "core/glint":
                return IrisPlan.Use.GLINT;
            case "core/rendertype_beacon_beam":
                return IrisPlan.Use.BEACON;
            case "core/rendertype_lightning":
                return IrisPlan.Use.LIGHTNING;
            case "core/position_tex":
                // The sun and the moon; other things drawn this way (the end flash, the GUI) are left to the game.
                return name.equals("pipeline/celestial") ? IrisPlan.Use.SKY_TEXTURED : null;
            default:
                return null;
        }
    }

    /**
     * The kind of thing a pipeline draws while the held item and arm are drawn: all of it is the hand, and a translucent item (stained glass) is the
     * translucent hand. The arm is drawn translucent like a player's skin, but it is solid, as Iris has it.
     */
    public static IrisPlan.@Nullable Use classifyHand(final RenderPipeline pipeline) {
        String shader = vertexOf(pipeline).getPath();
        if (!shader.equals("core/entity") && !shader.equals("core/item") && !shader.equals("core/block")) return null;
        return !shader.equals("core/entity") && pipeline.getLocation().getPath().contains("translucent") ? IrisPlan.Use.HAND_WATER : IrisPlan.Use.HAND;
    }

    /**
     * Whether a draw of the held item fills the pack's gbuffers ({@code gbuffers_hand}), so it is drawn ahead of the deferred programs; the rest (translucent
     * items, the glint) is drawn after them, in the game's own turn.
     */
    public static boolean isSolidHand(final RenderPipeline pipeline) {
        return classifyHand(COPIES.getOrDefault(pipeline, pipeline)) == IrisPlan.Use.HAND;
    }

    // ---- the sky ----

    private static @Nullable RenderPipeline sky;

    /**
     * Draws the pack's sky program over the whole screen, in the pass the game's sky is drawn in. Returns false (nothing is drawn) when the
     * pack has no such program.
     */
    public static boolean drawSky(final com.mojang.renderpearl.api.commands.RenderPass pass) {
        IrisPlan plan = IrisPipeline.currentPlan();
        IrisPlan.Step step = plan == null ? null : plan.worldStep(IrisPlan.Use.SKY);
        if (step == null) return false;
        if (sky == null) {
            String path = "iris_" + IrisPipeline.generation() + "_" + step.program().name() + "_sky";
            IDS.put(path, new Entry(IrisPipeline.generation(), step, IrisWorldAdapter.Format.NONE, Kind.SKY));
            ORIGINALS.put(path, new Identifier[] {Identifier.withDefaultNamespace("core/screenquad"), Identifier.withDefaultNamespace("core/screenquad")});
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/sky/" + IrisPipeline.generation()))
                    .withVertexShader(id)
                    .withFragmentShader(id)
                    .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
                    .withColorTargetState(0, ColorTargetState.DEFAULT);
            declareExtras(builder, plan, step);
            sky = builder.build();
        }
        try {
            pass.pushDebugGroup(() -> "Metallum Extra sky");
            pass.setPipeline(com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(sky));
            pass.draw(3, 1, 0, 0);
            pass.popDebugGroup();
        } catch (RuntimeException e) {
            // A sky that cannot be drawn is never a crash: the pack is put aside, and the game draws its own sky from the next frame.
            sky = null;
            // The group the draw opened is closed, or the pass is refused at its end for leaving one open.
            try {
                pass.popDebugGroup();
            } catch (RuntimeException ignored) {
            }
            if (!com.metallumextra.shader.pack.PackManager.fail(e)) throw e;
        }
        return true;
    }

    /** A builder with everything of {@code original} except its shaders, its color targets and its name. */
    private static RenderPipeline.Builder copy(final RenderPipeline original, final Identifier vertex, final Identifier fragment, final String name) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, name))
                .withVertexShader(vertex)
                .withFragmentShader(fragment)
                .withPrimitiveTopology(original.getPrimitiveTopology())
                .withCull(original.isCull())
                .withPolygonMode(original.getPolygonMode())
                .withDepthStencilState(Optional.ofNullable(original.getDepthStencilState()))
                .withPushConstantSize(original.pushConstantSize());
        for (BindGroupLayout layout : original.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        java.util.List<VertexFormat> bindings = original.getVertexFormatBindings();
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i) != null) builder.withVertexBinding(i, bindings.get(i));
        }
        original.getShaderDefines().values().forEach((define, value) -> {
            try {
                builder.withShaderDefine(define, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                builder.withShaderDefine(define, Float.parseFloat(value));
            }
        });
        for (String flag : original.getShaderDefines().flags()) builder.withShaderDefine(flag);
        return builder;
    }

    private static RenderPipeline make(final RenderPipeline original, final IrisPlan plan, final boolean hand, final int stage) {
        java.util.List<ColorTargetState> targets = original.getColorTargetStates();
        if (targets.isEmpty() || targets.get(0) == null || targets.get(0).format() != GpuFormat.RGBA8_UNORM) return original;
        // Terrain's pipelines are made with the pack's targets already (see SodiumTerrainPipelineMixin).
        if (targets.size() > 1) return original;

        IrisPlan.Use use = hand ? classifyHand(original) : classify(original);
        String stageSuffix = stage == 0 ? "" : "_stage" + stage;
        IrisPlan.Step step = use == null ? null : plan.worldStep(use);
        IrisWorldAdapter.Format format = IrisWorldAdapter.Format.of(original);
        if (step != null) {
            // What the program reads must be in the pipeline: its texture, and the light map if it asks for it.
            for (IrisPlan.Input input : step.inputs()) {
                if (input.name().equals("gtexture") && !format.atlas() || input.name().equals("lightmap") && !format.light()) {
                    if (LOGGED.add("skip|" + original.getLocation() + "|" + step.program().name())) {
                        MetallumExtra.LOGGER.info("[Metallum Extra] Pipeline {} cannot run {}: it has no {} sampler", original.getLocation(), step.program().name(), input.name());
                    }
                    step = null;
                    break;
                }
            }
        }
        if (step == null && plan.worldBuffers().isEmpty()) {
            passedThrough++;
            return original;
        }

        String location = original.getLocation().getNamespace() + "/" + original.getLocation().getPath();
        Identifier vertex = vertexOf(original);
        Identifier fragment = fragmentOf(original);
        if (step != null) {
            String path = "iris_" + IrisPipeline.generation() + "_" + step.program().name() + "_" + location.replace('/', '_').replace(':', '_') + stageSuffix;
            IDS.put(path, new Entry(IrisPipeline.generation(), step, format, Kind.WORLD, stage));
            ORIGINALS.put(path, new Identifier[] {vertex, fragment});
            vertex = Identifier.fromNamespaceAndPath("minecraft", path);
            fragment = vertex;
        }

        RenderPipeline.Builder builder = copy(original, vertex, fragment, "pipeline/world/" + IrisPipeline.generation() + "/" + location + stageSuffix);
        builder.withColorTargetState(0, targets.get(0));
        declareExtras(builder, plan, step);
        substituted++;
        if (LOG) {
            MetallumExtra.LOGGER.info("[Metallum Extra] world pipeline {} -> {} (vertex {}, fragment {}, format {})", original.getLocation(), step == null ? "game shader" : step.program().name(), vertexOf(original), fragmentOf(original), original.getVertexFormatBindings());
        }
        return builder.build();
    }

    /**
     * Declares the color targets every pass that draws the world has besides the first: one per buffer any program writes, in the
     * attachment {@link IrisPlan#attachment} gives it, and written only if {@code step} (the program drawing with this pipeline, or null) writes it.
     */
    public static void declareExtras(final RenderPipeline.Builder builder, final IrisPlan plan, final IrisPlan.@Nullable Step step) {
        ColorTargetState zero = ((com.metallumextra.mixin.RenderPipelineBuilderAccessor) builder).metallumExtra$colorTargetStates()[0];
        String program = step == null ? null : step.program().name();
        boolean writesZero = true;
        Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> blendOfFirst = Optional.empty();
        if (step != null && zero != null) {
            writesZero = false;
            for (int written : step.writes()) writesZero |= written == 0;
            Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> blend = zero.blendFunction();
            // The game's own blending belongs to the program's first output, wherever the pack sends it.
            if (!writesZero) blendOfFirst = blend;
            IrisPlan.BlendSetting setting = plan.blend(program, 0);
            if (setting instanceof IrisPlan.BlendSetting.Off) blend = Optional.empty();
            else if (setting instanceof IrisPlan.BlendSetting.Func func) blend = Optional.of(func.function());
            builder.withColorTargetState(0, new ColorTargetState(writesZero ? blend : Optional.empty(), zero.format(), writesZero ? zero.writeMask() : ColorTargetState.WRITE_NONE));
        }
        for (int buffer : plan.worldBuffers()) {
            // A buffer packed with others has no attachment of its own (see below).
            if (plan.packGroupOf(buffer) >= 0) continue;
            boolean writes = false;
            if (step != null) {
                for (int written : step.writes()) writes |= written == buffer;
            }
            Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> blend = Optional.empty();
            if (writes) {
                IrisPlan.BlendSetting setting = plan.blend(program, buffer);
                if (setting instanceof IrisPlan.BlendSetting.Func func) blend = Optional.of(func.function());
                else if (setting instanceof IrisPlan.BlendSetting.Default && buffer == step.writes()[0]) blend = blendOfFirst;
            }
            builder.withColorTargetState(plan.attachment(buffer), new ColorTargetState(blend, plan.buffers().get(buffer).format(), writes ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE));
        }
        // One attachment of four 32-bit integers for each group of packed buffers: the channels of the buffers the program writes are written, the others left as they are.
        int[] channelBits = {ColorTargetState.WRITE_RED, ColorTargetState.WRITE_GREEN, ColorTargetState.WRITE_BLUE, ColorTargetState.WRITE_ALPHA};
        for (int group = 0; group < plan.packGroups().size(); group++) {
            int mask = ColorTargetState.WRITE_NONE;
            if (step != null) {
                for (int buffer : plan.packGroups().get(group)) {
                    for (int written : step.writes()) {
                        if (written == buffer) mask |= channelBits[plan.packChannelOf(buffer)];
                    }
                }
            }
            builder.withColorTargetState(plan.attachmentOfGroup(group), new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_UINT, mask));
        }
    }
}
