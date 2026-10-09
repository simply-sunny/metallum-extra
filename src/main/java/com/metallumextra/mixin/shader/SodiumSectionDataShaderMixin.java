package com.metallumextra.mixin.shader;

import com.metallumextra.shader.ShadowPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shaders: a section has a new mesh (or none), so the shadow map's cached copy of the terrain is out of date. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage", remap = false)
public abstract class SodiumSectionDataShaderMixin {
    @Inject(method = "updateMeshes", at = @At("HEAD"))
    private void metallumExtra$meshChanged(final int localSectionIndex, final CallbackInfo ci) {
        ShadowPass.meshChanged();
    }
}
