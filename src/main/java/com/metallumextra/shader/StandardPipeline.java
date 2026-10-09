package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the programs of a standard (Iris layout) shader pack; see {@link ProgramSet}.
 * <p>
 * So far this is the {@code final} program only: after the world has been drawn, the image is copied and the pack's
 * {@code final} program draws it back to the screen, reading the copy as {@code colortex0} and its depth as
 * {@code depthtex0}. The pack's other programs are not run yet (the game's own shaders draw the world), and the
 * built-in effects (shadows, sky, bloom, edges) are off while a standard pack is in use.
 */
public final class StandardPipeline {
    /** What has been looked up for the dimension in use, so the files are not listed again every frame. */
    private static @Nullable ShaderPack discoveredFor;
    private static @Nullable String discoveredDimension;
    private static ProgramSet discovered = ProgramSet.discover(new com.metallumextra.shader.pack.BuiltinPack(), null);

    private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
    private static final Set<String> REPORTED = new HashSet<>();

    private StandardPipeline() {
    }

    /** Whether a standard pack is in use, whose programs run in place of the built-in effects. */
    public static boolean inUse() {
        return Shaders.active() && PackManager.active().standard();
    }

    /** The programs of the pack in use for the dimension the player is in. */
    public static ProgramSet programs() {
        ShaderPack pack = PackManager.active();
        Minecraft minecraft = Minecraft.getInstance();
        String dimension = minecraft.level == null ? null : ProgramSet.folderOf(minecraft.level.dimension().identifier().toString());
        if (pack != discoveredFor || !java.util.Objects.equals(dimension, discoveredDimension)) {
            discoveredFor = pack;
            discoveredDimension = dimension;
            discovered = ProgramSet.discover(pack, dimension);
            PIPELINES.clear();
            if (!discovered.unsupported().isEmpty() && REPORTED.add(pack.name() + "/" + dimension)) {
                MetallumExtra.LOGGER.info("[Metallum Extra] Shader pack {}: not run yet by this version: {}", pack.name(), String.join(", ", discovered.unsupported()));
            }
        }
        return discovered;
    }

    /** The pack or the dimension changed, or shaders were reloaded: everything is looked up and built again. */
    public static void forget() {
        discoveredFor = null;
        PIPELINES.clear();
    }

    /** After the world has been drawn: the pack's {@code final} program, if it has one. */
    public static void renderFinal(final RenderTarget main) {
        ProgramSet.Program program = programs().find("final");
        if (program == null) return;
        ShaderTargets targets = Shaders.targets();
        if (targets.scene() == null || main.width != targets.width() || main.height != targets.height()) return;

        RenderPipeline pipeline = PIPELINES.computeIfAbsent(program.name(), name -> build(program));
        targets.copyScene(main);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Metallum Extra final", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            for (String sampler : samplersOf(program)) {
                if (sampler.equals("colortex0")) pass.bindTexture(sampler, targets.scene().getColorTextureView(), linear);
                if (sampler.equals("depthtex0")) pass.bindTexture(sampler, targets.scene().getDepthTextureView(), nearest);
            }
            pass.draw(3, 1, 0, 0);
        }
    }

    /** The samplers a program declares, which is what its pipeline has to offer. */
    private static List<String> samplersOf(final ProgramSet.Program program) {
        ShaderPack pack = PackManager.active();
        return ProgramSet.samplers(pack.load(program.vertex()) + "\n" + pack.load(program.fragment()));
    }

    private static RenderPipeline build(final ProgramSet.Program program) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (String sampler : samplersOf(program)) layout.withSampler(sampler);
        Identifier id = Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "standard/" + program.name());
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "pipeline/standard/" + program.name()))
                .withVertexShader(id)
                .withFragmentShader(id)
                .withBindGroupLayout(layout.build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
    }
}
