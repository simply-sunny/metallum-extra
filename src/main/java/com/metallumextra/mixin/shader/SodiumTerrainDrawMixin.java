package com.metallumextra.mixin.shader;

import com.metallumextra.shader.IrisPipeline;
import com.metallumextra.shader.pack.IrisPlan;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shaders: while Sodium draws a layer of terrain, the render pass it makes is for terrain, and gets the buffers the pack's program writes. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class SodiumTerrainDrawMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void metallumExtra$beginTerrain(final net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices matrices,
                                            final net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable lists, final TerrainRenderPass pass,
                                            final net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform camera, final net.caffeinemc.mods.sodium.client.util.FogParameters fog,
                                            final boolean flag, final com.mojang.blaze3d.textures.GpuSampler sampler, final com.mojang.blaze3d.buffers.GpuBufferSlice slice,
                                            final com.mojang.blaze3d.buffers.GpuBuffer buffer, final CallbackInfo ci) {
        IrisPlan.Layer layer = pass == DefaultTerrainRenderPasses.TRANSLUCENT || pass.isTranslucent() ? IrisPlan.Layer.TRANSLUCENT
                : pass.supportsFragmentDiscard() ? IrisPlan.Layer.CUTOUT : IrisPlan.Layer.SOLID;
        IrisPipeline.beginTerrain(layer);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metallumExtra$endTerrain(final net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices matrices,
                                          final net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable lists, final TerrainRenderPass pass,
                                          final net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform camera, final net.caffeinemc.mods.sodium.client.util.FogParameters fog,
                                          final boolean flag, final com.mojang.blaze3d.textures.GpuSampler sampler, final com.mojang.blaze3d.buffers.GpuBufferSlice slice,
                                          final com.mojang.blaze3d.buffers.GpuBuffer buffer, final CallbackInfo ci) {
        IrisPipeline.endTerrain();
    }
}
