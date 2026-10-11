package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.MetalVoxyRendererManager;
import com.metallumextra.voxy.VoxyMetalCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same two points where Voxy swaps and rebuilds its renderer get the Metal one: a level change
 * drops the old renderer, and a reload tears down and rebuilds it against the new world state.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorVoxyMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metallumExtra$voxySetLevel(final ClientLevel level, final CallbackInfo ci) {
        if (VoxyMetalCompat.enabled()) {
            MetalVoxyRendererManager.onSetLevel(level);
        }
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void metallumExtra$voxyReload(final CallbackInfo ci) {
        if (VoxyMetalCompat.enabled()) {
            MetalVoxyRendererManager.onAllChanged();
        }
    }
}
