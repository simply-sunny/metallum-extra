package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.gl.VoxyGlRegistry;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same backing as {@link GlBufferMixin} with one extra step: the "persistent mapping". On a driver,
 * {@code nglMapNamedBufferRange} returns the GPU-visible pointer Voxy writes its uploads through;
 * here the map call simply resolves to the registry's off-heap address, so {@code addr()} hands out
 * real CPU memory and {@code UploadStream} becomes a pure staging allocator over it.
 */
@Mixin(value = GlPersistentMappedBuffer.class, remap = false)
public abstract class GlPersistentMappedBufferMixin {
    /** Mints the fake id (see {@link GlBufferMixin}). */
    @Redirect(method = "<init>(JI)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glCreateBuffers()I"))
    private static int metallumExtra$fakeBufferId() {
        return VoxyGlRegistry.allocBufferId();
    }

    /** {@code glNamedBufferStorage}: register the CPU allocation. */
    @Redirect(method = "<init>(JI)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glNamedBufferStorage(IJI)V"))
    private static void metallumExtra$allocateStorage(final int id, final long size, final int flags) {
        VoxyGlRegistry.allocBuffer(id, size, 0L, true);
    }

    /** {@code nglMapNamedBufferRange}: the "mapping" is the registered allocation itself. */
    @Redirect(method = "<init>(JI)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglMapNamedBufferRange(IJJI)J"))
    private static long metallumExtra$mapRange(final int id, final long offset, final long length, final int access) {
        return VoxyGlRegistry.address(id);
    }

    /** {@code glUnmapNamedBuffer}: nothing to unmap, the free below releases the memory. */
    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glUnmapNamedBuffer(I)Z"))
    private static boolean metallumExtra$unmapNoop(final int id) {
        return false;
    }

    /** {@code glDeleteBuffers}: free the registry entry. */
    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glDeleteBuffers(I)V"))
    private static void metallumExtra$deleteBuffer(final int id) {
        VoxyGlRegistry.free(id);
    }
}
