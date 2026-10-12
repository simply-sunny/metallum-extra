package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.MetalVoxyRendererManager;
import com.metallumextra.voxy.VoxyMetalCompat;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The level renderer closing takes the Metal Voxy renderer with it, as it does Voxy's own, and the
 * end of its {@code render} is where the LOD field is drawn.
 *
 * <p>The frame is anchored here rather than earlier because the level renderer's frame graph has
 * already executed by then — sky, terrain, clouds and weather are all in the main target — so the
 * LOD terrain lands on top of the world and hands a full main target to whatever runs after (the
 * hand, the HUD, post effects). Nothing about the hook depends on a shader pack being active.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVoxyMixin {
    private static final String RENDER = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V";

    @Inject(method = "close", at = @At("HEAD"))
    private void metallumExtra$voxyClose(final CallbackInfo ci) {
        if (VoxyMetalCompat.enabled()) {
            MetalVoxyRendererManager.onClose();
        }
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void metallumExtra$voxyFrame(final GraphicsResourceAllocator allocator, final boolean renderBlockOutline,
            final CameraRenderState cameraState, final GpuBufferSlice fogBuffer, final Vector4f clearColour,
            final boolean renderClouds, final boolean frozen, final CallbackInfo ci) {
        if (VoxyMetalCompat.enabled()) {
            MetalVoxyRendererManager.frame(cameraState);
        }
    }
}
