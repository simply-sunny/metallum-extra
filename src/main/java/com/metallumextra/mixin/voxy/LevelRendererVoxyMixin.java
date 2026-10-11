package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.MetalVoxyRendererManager;
import com.metallumextra.voxy.VoxyMetalCompat;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The level renderer closing takes the Metal Voxy renderer with it, as it does Voxy's own. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVoxyMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void metallumExtra$voxyClose(final CallbackInfo ci) {
        if (VoxyMetalCompat.enabled()) {
            MetalVoxyRendererManager.onClose();
        }
    }
}
