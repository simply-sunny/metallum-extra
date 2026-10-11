package com.metallumextra.mixin.voxy;

import me.cortex.voxy.client.core.gl.GlFence;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Voxy fences GPU upload frames so its allocator can recycle staging memory a few frames later.
 * With {@link GlPersistentMappedBufferMixin} everything is CPU memory whose writes are complete the
 * moment they happen, so no GPU wait is ever required: the sync object is a dummy (0) and every
 * status poll reports {@code GL_SIGNALED}, which makes {@code UploadStream.tick} release frame
 * allocations immediately and unmodified.
 */
@Mixin(value = GlFence.class, remap = false)
public abstract class GlFenceMixin {
    /** {@code glFenceSync}: a dummy sync object; CPU "commands" are already finished. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glFenceSync(II)J"))
    private static long metallumExtra$dummySync(final int condition, final int flags) {
        return 0L;
    }

    /** {@code nglGetSynciv}: report GL_SIGNALED so {@code signaled()} flips true on first poll. */
    @Redirect(method = "signaled", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;nglGetSynciv(JIIJJ)V"))
    private static void metallumExtra$alwaysSignaled(final long sync, final int pname, final int count, final int flags, final long values) {
        MemoryUtil.memPutInt(values, 1/* GL_SIGNALED */);
    }

    /** {@code glDeleteSync}: nothing to delete. */
    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glDeleteSync(J)V"))
    private static void metallumExtra$deleteNoop(final long sync) {
    }
}
