package com.metallumextra.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.metallum.render.MetallumExtraNative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Compute programs write the pack's buffers, which the game makes as textures to draw into: Metal has to be told they are written to as well. */
@Mixin(targets = "com.metallum.render.MetalGpuTexture", remap = false)
public abstract class MetalGpuTextureMixin {
    @ModifyReturnValue(method = "toMtlTextureUsage", at = @At("RETURN"))
    private static long metallumExtra$storage(final long usage, final int flags) {
        return MetallumExtraNative.usage(usage, flags);
    }
}
