package com.metallumextra.voxy.gl;

import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CPU-side stand-in for the OpenGL object namespaces Voxy allocates into (buffers and textures).
 * Under Metallum's Metal backend there is no GL context, so every GL "name" Voxy would have received
 * from glGenTextures/glCreateBuffers-style calls is instead a unique fake id minted here and mapped
 * to plain off-heap memory. Voxy's own code keeps using its {@code public final int id} fields
 * unchanged; the mixins redirect the raw GL calls onto this registry, so buffer content operations
 * become memcpy/memset over the registered addresses and the later Metal stage can read the exact
 * bytes Voxy produced.
 *
 * <p>Uploads may happen off-thread (Voxy's model factory runs on its own worker), so all maps are
 * concurrent.</p>
 */
public final class VoxyGlRegistry {
    /** Fake buffer ids live here so they can never collide with real GL names even if one existed. */
    private static final int BUFFER_ID_BASE = 0x7F00_0000;
    /** Fake texture ids use a different range so buffer/texture namespaces stay disjoint. */
    private static final int TEXTURE_ID_BASE = 0x3F00_0000;

    private static final AtomicInteger BUFFER_ID_COUNTER = new AtomicInteger();
    private static final AtomicInteger TEXTURE_ID_COUNTER = new AtomicInteger();

    /** One registered buffer: its off-heap allocation and length in bytes. */
    public record Buffer(long address, long size) {}

    /** Book-keeping only for textures (the model atlas cell writes are captured into {@link ModelAtlasMirror}). */
    public record Texture(int width, int height, int levels) {}

    private static final ConcurrentHashMap<Integer, Buffer> BUFFERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Texture> TEXTURES = new ConcurrentHashMap<>();

    private VoxyGlRegistry() {
    }

    /** Mints a fresh fake buffer id (replacement for {@code glCreateBuffers()}). */
    public static int allocBufferId() {
        return BUFFER_ID_BASE | (BUFFER_ID_COUNTER.incrementAndGet() & 0x00FF_FFFF);
    }

    /** Mints a fresh fake texture id (replacement for {@code glCreateTextures()}/{@code glGenTextures()}). */
    public static int allocTextureId() {
        return TEXTURE_ID_BASE | (TEXTURE_ID_COUNTER.incrementAndGet() & 0x00FF_FFFF);
    }

    /**
     * Registers {@code id} with a fresh {@code size}-byte allocation (replacement for
     * {@code nglNamedBufferStorage}). The memory is zeroed, then seeded from {@code dataPtr} when
     * Voxy passed initial data (mirrors how GL copies {@code data} into the store).
     */
    public static void allocBuffer(final int id, final long size, final long dataPtr, final boolean zero) {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Ridiculous buffer size: " + size);
        }
        final long address = MemoryUtil.nmemAlloc(size);
        if (address == 0L) {
            throw new OutOfMemoryError("Failed to allocate " + size + " bytes for fake GL buffer " + id);
        }
        if (zero || dataPtr == 0L) {
            MemoryUtil.memSet(address, 0, size);
        }
        if (dataPtr != 0L) {
            UnsafeUtil.memcpy(dataPtr, address, size);
        }
        BUFFERS.put(id, new Buffer(address, size));
    }

    /** Registers {@code id} as an allocation-free texture record (replacement for {@code glTextureStorage2D}). */
    public static void allocTexture(final int id, final int width, final int height, final int levels) {
        TEXTURES.put(id, new Texture(width, height, levels));
    }

    /** The registered buffer address for {@code id}, or 0 when unknown. */
    public static long address(final int id) {
        final Buffer buffer = BUFFERS.get(id);
        return buffer == null ? 0L : buffer.address();
    }

    /** The registered buffer size for {@code id}, or 0 when unknown. */
    public static long size(final int id) {
        final Buffer buffer = BUFFERS.get(id);
        return buffer == null ? 0L : buffer.size();
    }

    /** Texture book-keeping for {@code id}, or null when unknown. */
    public static Texture texture(final int id) {
        return TEXTURES.get(id);
    }

    /** {@code glCopyNamedBufferSubData}: a plain memcpy between the two registered allocations. */
    public static void copy(final int srcId, final int dstId, final long srcOff, final long dstOff, final long size) {
        final Buffer src = BUFFERS.get(srcId);
        final Buffer dst = BUFFERS.get(dstId);
        if (src == null || dst == null) {
            // On a real driver this would be a GL error; skip and complain rather than corrupt memory.
            com.metallumextra.MetallumExtra.LOGGER.error(
                    "[Metallum Extra] Voxy copy on unknown fake buffer (src={}, dst={}), dropped", srcId, dstId);
            return;
        }
        if (srcOff < 0 || dstOff < 0 || size < 0 || srcOff + size > src.size() || dstOff + size > dst.size()) {
            throw new IllegalArgumentException("Out of bounds fake buffer copy: src=" + srcId + "[" + srcOff + "+" + size
                    + " of " + src.size() + "] dst=" + dstId + "[" + dstOff + "+" + size + " of " + dst.size() + "]");
        }
        UnsafeUtil.memcpy(src.address() + srcOff, dst.address() + dstOff, size);
    }

    /** Frees a fake buffer or texture id ({@code glDeleteBuffers}/{@code glDeleteTextures}). Safe on unknown ids. */
    public static void free(final int id) {
        final Buffer buffer = BUFFERS.remove(id);
        if (buffer != null) {
            MemoryUtil.nmemFree(buffer.address());
        }
        TEXTURES.remove(id);
    }

    /** Frees every registered allocation. Called by the later stage on world shutdown. */
    public static void freeAll() {
        BUFFERS.values().forEach(buffer -> MemoryUtil.nmemFree(buffer.address()));
        BUFFERS.clear();
        TEXTURES.clear();
    }
}
