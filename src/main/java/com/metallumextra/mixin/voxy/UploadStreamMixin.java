package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.gl.VoxyGlRegistry;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * With the persistent-mapped buffer shimmed, Voxy's upload stream is already a CPU staging
 * allocator; what remains are its explicit GPU synchronisation points. The "flush mapped range"
 * calls and the memory barriers have no meaning for CPU memory, and the commit-time
 * {@code glCopyNamedBufferSubData} becomes a memcpy between the two registered allocations, so
 * staged model/colour data lands verbatim in the buffers the later Metal stage reads. Shimming the
 * stream itself (rather than its call sites in ModelFactory) keeps every caller correct.
 */
@Mixin(value = UploadStream.class, remap = false)
public abstract class UploadStreamMixin {
    /** {@code glFlushMappedNamedBufferRange} (full-stream fallback in rawUploadAddress): no-op. */
    @Redirect(method = "rawUploadAddress", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glFlushMappedNamedBufferRange(IJJ)V"))
    private static void metallumExtra$flushNoop(final int buffer, final long offset, final long length) {
    }

    /** {@code glFinish} (emergency drain when the stream is full): CPU memory needs no drain. */
    @Redirect(method = "rawUploadAddress", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glFinish()V"))
    private static void metallumExtra$finishNoop() {
    }

    /** {@code glFlushMappedNamedBufferRange} (commit): no-op. */
    @Redirect(method = "commit", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glFlushMappedNamedBufferRange(IJJ)V"))
    private static void metallumExtra$commitFlushNoop(final int buffer, final long offset, final long length) {
    }

    /** {@code glCopyNamedBufferSubData}: copy staged bytes into the target buffer's CPU allocation. */
    @Redirect(method = "commit", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/ARBDirectStateAccess;glCopyNamedBufferSubData(IIJJJ)V"))
    private static void metallumExtra$copyBuffers(final int srcId, final int dstId, final long srcOffset,
                                                  final long dstOffset, final long size) {
        VoxyGlRegistry.copy(srcId, dstId, srcOffset, dstOffset, size);
    }

    /** {@code glMemoryBarrier} (before/after the copies): no-op. */
    @Redirect(method = "commit", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL42;glMemoryBarrier(I)V"))
    private static void metallumExtra$barrierNoop(final int barriers) {
    }
}
