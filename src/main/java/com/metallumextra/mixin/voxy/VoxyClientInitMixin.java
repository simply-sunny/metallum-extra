package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.VoxyMetalCompat;
import me.cortex.voxy.client.VoxyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy's renderer starts by probing OpenGL capabilities. Under the Metal backend there is no OpenGL
 * context, so that probe throws and the game would not get past startup. Cancel the whole init on
 * Metal and let {@link VoxyMetalCompat} keep Voxy's data side (ingest, storage, imports) running.
 */
@Mixin(value = VoxyClient.class, remap = false)
public abstract class VoxyClientInitMixin {
    @Inject(method = "initVoxyClient", at = @At("HEAD"), cancellable = true)
    private static void metallumExtra$voxyInitOnMetal(final CallbackInfo ci) {
        if (VoxyMetalCompat.isMetalBackend()) {
            VoxyMetalCompat.onMetalBackend();
            ci.cancel();
        }
    }
}
