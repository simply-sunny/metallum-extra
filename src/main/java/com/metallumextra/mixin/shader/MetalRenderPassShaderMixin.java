package com.metallumextra.mixin.shader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shaders: the game draws weather with no index at all while no rain is falling; drawing nothing is not an error for Metal's validation layer only because it is skipped here. */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassShaderMixin {
    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$skipEmptyDraw(final int indexCount, final int instanceCount, final int firstIndex, final int baseVertex, final int baseInstance, final CallbackInfo ci) {
        if (indexCount == 0) ci.cancel();
    }
}
