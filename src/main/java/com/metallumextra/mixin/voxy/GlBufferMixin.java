package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.gl.VoxyGlRegistry;
import me.cortex.voxy.client.core.gl.GlBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Backs Voxy's DSA buffers with off-heap CPU memory. {@code public final int id} keeps flowing
 * through Voxy's own code, but it now names an entry in {@link VoxyGlRegistry} instead of a GL
 * object: creation allocates and optionally seeds the memory, the clear/fill operations become
 * memset over the registered address, deletion frees it. Everything downstream (including
 * {@code UploadStream}'s staged copies) therefore operates on real bytes that the later Metal
 * stage can read directly.
 */
@Mixin(value = GlBuffer.class, remap = false)
public abstract class GlBufferMixin {
    /** Mints a fake id; the registry allocates the backing memory (see the storage redirect below). */
    @Redirect(method = "<init>(JIZJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glCreateBuffers()I"))
    private static int metallumExtra$fakeBufferId() {
        return VoxyGlRegistry.allocBufferId();
    }

    /** {@code nglNamedBufferStorage}: allocate the CPU memory, seeding it from {@code data} when given. */
    @Redirect(method = "<init>(JIZJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglNamedBufferStorage(IJJI)V"))
    private static void metallumExtra$allocateStorage(final int id, final long size, final long data, final int flags) {
        VoxyGlRegistry.allocBuffer(id, size, data, true);
    }

    /** {@code glDeleteBuffers}: free the registry entry (safe if already freed by freeAll). */
    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glDeleteBuffers(I)V"))
    private static void metallumExtra$deleteBuffer(final int id) {
        VoxyGlRegistry.free(id);
    }

    /** {@code zero()}: GL cleared the whole store; memset the registered allocation instead. */
    @Redirect(method = "zero", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglClearNamedBufferData(IIIIJ)V"))
    private static void metallumExtra$clearBuffer(final int id, final int internalFormat, final int format, final int type, final long data) {
        final long address = VoxyGlRegistry.address(id);
        if (address != 0L) {
            MemoryUtil.memSet(address, 0, VoxyGlRegistry.size(id));
        }
    }

    /** {@code zeroRange()}: GL cleared a sub-range; memset just that span. */
    @Redirect(method = "zeroRange", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglClearNamedBufferSubData(IIJJIIJ)V"))
    private static void metallumExtra$clearBufferRange(final int id, final int internalFormat, final long offset, final long size,
                                                       final int format, final int type, final long data) {
        final long address = VoxyGlRegistry.address(id);
        if (address != 0L) {
            MemoryUtil.memSet(address + offset, 0, size);
        }
    }

    /** {@code fill()}: GL broadcast a 32-bit pattern across the store; do the same on the CPU. */
    @Redirect(method = "fill", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglClearNamedBufferData(IIIIJ)V"))
    private static void metallumExtra$fillBuffer(final int id, final int internalFormat, final int format, final int type, final long data) {
        final long address = VoxyGlRegistry.address(id);
        if (address == 0L) {
            return;
        }
        final long size = VoxyGlRegistry.size(id);
        if (data == 0L) {
            MemoryUtil.memSet(address, 0, size);
            return;
        }
        final int value = MemoryUtil.memGetInt(data);
        for (long at = address; at < address + size; at += 4) {
            MemoryUtil.memPutInt(at, value);
        }
    }

    /** {@code fill()}'s unpack-state resets are meaningless without a driver; drop them. */
    @Redirect(method = "fill", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glPixelStorei(II)V"))
    private static void metallumExtra$pixelStoreNoop(final int pname, final int param) {
    }
}
