package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.metallumextra.shader.IrisPipeline;
import com.metallumextra.shader.pack.IrisPlan;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Optional;

/**
 * Shaders: while a standard pack is in use, Sodium's pipeline for a layer of terrain is made with the shader of the pack's program for
 * that layer, and declares the extra buffers that program writes. (Sodium keeps the pipelines; see {@code SodiumTerrain}.)
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class SodiumTerrainPipelineMixin {
    private static IrisPlan.Layer metallumExtra$layer(final TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT || pass.isTranslucent()) return IrisPlan.Layer.TRANSLUCENT;
        return pass.supportsFragmentDiscard() ? IrisPlan.Layer.CUTOUT : IrisPlan.Layer.SOLID;
    }

    @WrapOperation(method = "createShader", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withVertexShader(Lnet/minecraft/resources/Identifier;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
    private RenderPipeline.Builder metallumExtra$vertexShader(final RenderPipeline.Builder builder, final Identifier id, final Operation<RenderPipeline.Builder> original,
                                                              @Local(argsOnly = true) final TerrainRenderPass pass) {
        Identifier ours = IrisPipeline.terrainShaderId(metallumExtra$layer(pass));
        return original.call(builder, ours != null ? ours : id);
    }

    @WrapOperation(method = "createShader", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withFragmentShader(Lnet/minecraft/resources/Identifier;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
    private RenderPipeline.Builder metallumExtra$fragmentShader(final RenderPipeline.Builder builder, final Identifier id, final Operation<RenderPipeline.Builder> original,
                                                                @Local(argsOnly = true) final TerrainRenderPass pass) {
        Identifier ours = IrisPipeline.terrainShaderId(metallumExtra$layer(pass));
        return original.call(builder, ours != null ? ours : id);
    }

    @WrapOperation(method = "createShader", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;build()Lcom/mojang/blaze3d/pipeline/RenderPipeline;"))
    private RenderPipeline metallumExtra$build(final RenderPipeline.Builder builder, final Operation<RenderPipeline> original, @Local(argsOnly = true) final TerrainRenderPass pass) {
        IrisPlan.Layer layer = metallumExtra$layer(pass);
        if (IrisPipeline.terrainShaderId(layer) != null) {
            List<GpuFormat> extra = IrisPipeline.terrainExtraFormats(layer);
            for (int i = 0; i < extra.size(); i++) {
                builder.withColorTargetState(i + 1, new ColorTargetState(Optional.empty(), extra.get(i), ColorTargetState.WRITE_ALL));
            }
        }
        return original.call(builder);
    }
}
