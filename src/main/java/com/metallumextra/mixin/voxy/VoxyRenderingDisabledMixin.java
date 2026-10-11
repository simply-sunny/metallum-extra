package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.VoxyMetalCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy creates its OpenGL render system only when this says rendering is enabled. On the Metal
 * backend that construction would crash on the first GL call, so it always says no there — while
 * leaving the answer untouched on any OpenGL system, where Voxy keeps working on its own.
 */
@Mixin(value = VoxyConfig.class, remap = false)
public abstract class VoxyRenderingDisabledMixin {
    @Inject(method = "isRenderingEnabled", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$noGlRendererOnMetal(final CallbackInfoReturnable<Boolean> cir) {
        if (VoxyMetalCompat.isMetalBackend()) {
            cir.setReturnValue(false);
        }
    }
}
