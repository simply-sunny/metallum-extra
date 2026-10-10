package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallumextra.shader.IrisWorld;
import com.metallumextra.shader.Shaders;
import com.metallumextra.shader.pack.PackManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shaders: a pipeline that is compiled as a pass first draws with it; see {@link MetalDeviceShaderMixin}. */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassShaderMixin {
    /** The game draws weather with no index at all while no rain is falling; drawing nothing is not an error for Metal's validation layer only because it is skipped here. */
    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$skipEmptyDraw(final int indexCount, final int instanceCount, final int firstIndex, final int baseVertex, final int baseInstance, final CallbackInfo ci) {
        if (indexCount == 0) ci.cancel();
    }

    @WrapMethod(method = "setPipeline")
    private void metallumExtra$fallBackFromBrokenPack(final RenderPipeline pipeline, final Operation<Void> original) {
        try {
            // While a standard pack draws the world, the game's pipelines are swapped for the pack's copies of them.
            original.call(IrisWorld.pipelineFor(pipeline));
        } catch (RuntimeException e) {
            if (!Shaders.active() || !PackManager.fail(e)) throw e;
            // The pack's copy of the pipeline is the one that failed: the game's own is drawn with for the rest of this frame.
            original.call(pipeline);
        }
    }
}
