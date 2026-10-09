package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.IrisPlan;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    public record Entry(int generation, IrisPlan.Step step, IrisWorldAdapter.Format format, Kind kind) {
    }

    /** What a program is made to draw: the world, the shadow map, or the sky behind everything. */
    public enum Kind {
        WORLD, SHADOW, SKY
    }

    private IrisWorld() {
    }

    /** Called when the plan changes or the pack is put away: the copies were made for the old one. */
    public static void forget() {
        VARIANTS.clear();
        COPIES.clear();
        HAND_VARIANTS.clear();
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
        Map<RenderPipeline, RenderPipeline> variants = hand ? HAND_VARIANTS : VARIANTS;
        RenderPipeline known = variants.get(original);
        if (known != null) return known;
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null) return original;
        RenderPipeline made = make(original, plan, hand);
        variants.put(original, made);
        COPIES.put(made, original);
        return made;
    }

    /** The copy of a model pipeline that draws it with the pack's shadow program; the pipeline itself when it casts no shadow (the draw is then dropped). */
    private static RenderPipeline shadowPipelineFor(final RenderPipeline original) {
        if (IrisPipeline.inStage()) return original;
        RenderPipeline known = SHADOW_VARIANTS.get(original);
        if (known != null) return known;
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan == null) return original;
        RenderPipeline made = makeShadow(original, plan);
        SHADOW_VARIANTS.put(original, made);
        if (made != original) {
            SHADOW_SET.add(made);
            COPIES.put(made, original);
        }
        return made;
    }

    private static RenderPipeline makeShadow(final RenderPipeline original, final IrisPlan plan) {
        String vertexPath = original.getVertexShader().getPath();
        IrisPlan.Use use = vertexPath.equals("core/block") ? IrisPlan.Use.SHADOW_BLOCK
                : vertexPath.equals("core/entity") || vertexPath.equals("core/item") ? IrisPlan.Use.SHADOW_ENTITY : null;
        IrisPlan.Step step = use == null ? null : plan.worldStep(use);
        ColorTargetState[] targets = original.getColorTargetStates();
        if (step == null || targets.length == 0 || targets[0] == null || targets[0].format() != GpuFormat.RGBA8_UNORM) return original;
        IrisWorldAdapter.Format format = IrisWorldAdapter.Format.of(original);
        for (IrisPlan.Input input : step.inputs()) {
            if (input.name().equals("gtexture") && !format.atlas() || input.name().equals("lightmap") && !format.light()) return original;
        }
        String location = original.getLocation().getNamespace() + "/" + original.getLocation().getPath();
        String path = "iris_" + IrisPipeline.generation() + "_" + step.program().name() + "_shadow_" + location.replace('/', '_').replace(':', '_');
        IDS.put(path, new Entry(IrisPipeline.generation(), step, format, Kind.SHADOW));
        ORIGINALS.put(path, new Identifier[] {original.getVertexShader(), original.getFragmentShader()});
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", path);
        RenderPipeline.Builder builder = copy(original, id, id, "pipeline/shadow/" + IrisPipeline.generation() + "/" + location);
        // OpenGL depth: the map is cleared to 1 and the nearest to the light wins.
        builder.withDepthStencilState(Optional.of(new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.LESS_THAN_OR_EQUAL, true)));
        builder.withColorTargetState(0, targets[0]);
        return builder.build();
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
        String shader = pipeline.getVertexShader().getPath();
        String name = pipeline.getLocation().getPath();
        boolean translucent = name.contains("translucent");
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
            default:
                return null;
        }
    }

    /** The kind of thing a pipeline draws while the held item and arm are drawn: all of it is the hand. */
    public static IrisPlan.@Nullable Use classifyHand(final RenderPipeline pipeline) {
        String shader = pipeline.getVertexShader().getPath();
        if (!shader.equals("core/entity") && !shader.equals("core/item") && !shader.equals("core/block")) return null;
        return pipeline.getLocation().getPath().contains("translucent") ? IrisPlan.Use.HAND_WATER : IrisPlan.Use.HAND;
    }

    // ---- the sky ----

    private static @Nullable RenderPipeline sky;

    /**
     * Draws the pack's sky program over the whole screen, in the pass the game's sky is drawn in. Returns false (nothing is drawn) when the
     * pack has no such program.
     */
    public static boolean drawSky(final com.mojang.blaze3d.pipeline.RenderTarget target) {
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
                    .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                    .withColorTargetState(0, ColorTargetState.DEFAULT);
            declareExtras(builder, plan, step);
            sky = builder.build();
        }
        try (com.mojang.blaze3d.systems.RenderPass pass = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Metallum Extra sky", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(sky);
            pass.draw(3, 1, 0, 0);
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
                .withDepthStencilState(Optional.ofNullable(original.getDepthStencilState()));
        for (BindGroupLayout layout : original.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        VertexFormat[] bindings = original.getVertexFormatBindings();
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] != null) builder.withVertexBinding(i, bindings[i]);
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

    private static RenderPipeline make(final RenderPipeline original, final IrisPlan plan, final boolean hand) {
        ColorTargetState[] targets = original.getColorTargetStates();
        if (targets.length == 0 || targets[0] == null || targets[0].format() != GpuFormat.RGBA8_UNORM) return original;
        // Terrain's pipelines are made with the pack's targets already (see SodiumTerrainPipelineMixin).
        if (targets.length > 1) return original;

        IrisPlan.Use use = hand ? classifyHand(original) : classify(original);
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
        Identifier vertex = original.getVertexShader();
        Identifier fragment = original.getFragmentShader();
        if (step != null) {
            String path = "iris_" + IrisPipeline.generation() + "_" + step.program().name() + "_" + location.replace('/', '_').replace(':', '_');
            IDS.put(path, new Entry(IrisPipeline.generation(), step, format, Kind.WORLD));
            ORIGINALS.put(path, new Identifier[] {vertex, fragment});
            vertex = Identifier.fromNamespaceAndPath("minecraft", path);
            fragment = vertex;
        }

        RenderPipeline.Builder builder = copy(original, vertex, fragment, "pipeline/world/" + IrisPipeline.generation() + "/" + location);
        builder.withColorTargetState(0, targets[0]);
        declareExtras(builder, plan, step);
        substituted++;
        if (LOG) {
            MetallumExtra.LOGGER.info("[Metallum Extra] world pipeline {} -> {}", original.getLocation(), step == null ? "game shader" : step.program().name());
        }
        return builder.build();
    }

    /**
     * Declares the color targets every pass that draws the world has besides the first: one per buffer any program writes, in the
     * attachment of its number, and written only if {@code step} (the program drawing with this pipeline, or null) writes it.
     */
    public static void declareExtras(final RenderPipeline.Builder builder, final IrisPlan plan, final IrisPlan.@Nullable Step step) {
        int last = plan.worldBuffers().isEmpty() ? 0 : java.util.Collections.max(plan.worldBuffers());
        for (int slot = 1; slot <= last; slot++) {
            if (!plan.worldBuffers().contains(slot)) {
                builder.withUnusedColorTargetState(slot);
                continue;
            }
            boolean writes = false;
            if (step != null) {
                for (int written : step.writes()) writes |= written == slot;
            }
            builder.withColorTargetState(slot, new ColorTargetState(Optional.empty(), plan.buffers().get(slot).format(), writes ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE));
        }
    }
}
