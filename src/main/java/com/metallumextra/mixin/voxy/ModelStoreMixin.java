package com.metallumextra.mixin.voxy;

import me.cortex.voxy.client.core.model.ModelStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The model store's sampler object is pure GL state (filter/wrap configuration for the model atlas
 * texture unit) with no CPU-side meaning, so under Metal it becomes a dummy id whose configuration
 * calls are dropped. Its two {@code GlBuffer}s and the shared atlas {@code GlTexture} are already
 * covered by the {@link GlBufferMixin}/{@link GlTextureMixin} shims, which is what makes
 * {@code new ModelStore()} fully GL-free.
 */
@Mixin(value = ModelStore.class, remap = false)
public abstract class ModelStoreMixin {
    /** {@code glGenSamplers} (field initializer): a dummy sampler id. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33;glGenSamplers()I"))
    private static int metallumExtra$dummySampler() {
        return 0;
    }

    /** {@code glSamplerParameteri} (min/mag filter, min/max LOD): nothing to configure on Metal. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33C;glSamplerParameteri(III)V"))
    private static void metallumExtra$samplerParameterNoop(final int sampler, final int pname, final int param) {
    }

    /** {@code glDeleteSamplers}: nothing to delete. */
    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33;glDeleteSamplers(I)V"))
    private static void metallumExtra$deleteSamplerNoop(final int sampler) {
    }
}
