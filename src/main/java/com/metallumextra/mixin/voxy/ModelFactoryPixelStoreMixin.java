package com.metallumextra.mixin.voxy;

import me.cortex.voxy.client.core.model.ModelFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code processUploads} only sets GL unpack state before pushing staged CPU data through
 * {@code UploadStream} (which is itself shimmed) and the per-model atlas write (redirected by
 * {@link ModelFactoryUploadMixin}). Without a driver there is no unpack state to set, so the four
 * {@code glPixelStorei} calls are dropped. This is the entirety of the GL reached by
 * {@code ModelBakerySubsystem.tick}.
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelFactoryPixelStoreMixin {
    /** All {@code glPixelStorei} calls in processUploads (row length, skip pixels/rows, alignment). */
    @Redirect(method = "processUploads", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPixelStorei(II)V"))
    private static void metallumExtra$pixelStoreNoop(final int pname, final int param) {
    }
}
