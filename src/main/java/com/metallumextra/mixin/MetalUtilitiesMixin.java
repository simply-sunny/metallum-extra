package com.metallumextra.mixin;

import com.metallumextra.NonBlockingPresent;
import io.github.kokodio.metaljvm.quartzcore.CAMetalDrawable;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Fix: never block the render thread waiting for a swapchain image when vsync is off. */
@Mixin(targets = "com.metallum.render.MetalUtilities", remap = false)
public abstract class MetalUtilitiesMixin {
    @Redirect(method = "encodePresentTextureToDrawable",
            at = @At(value = "INVOKE", target = "Lio/github/kokodio/metaljvm/quartzcore/CAMetalLayer;nextDrawable()Lio/github/kokodio/metaljvm/quartzcore/CAMetalDrawable;"))
    private static CAMetalDrawable metallumExtra$nextDrawable(final CAMetalLayer layer) {
        return NonBlockingPresent.nextDrawable(layer);
    }
}
