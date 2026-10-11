package com.metallumextra.voxy.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CPU mirror of Voxy's baked model atlas. On a GL driver every model bake ends in an
 * {@code nglTextureSubImage2D} into Voxy's 768x512 RGBA atlas; under Metal that call is redirected
 * into {@link #writeCell}, which copies the same raw RGBA bytes (one 48x32 cell per model id, the
 * exact layout {@link com.metallumextra.mixin.voxy.ModelFactoryUploadMixin} derives from the GL
 * coordinates) into one flat CPU buffer in our own compact grid. The later Metal stage uploads
 * {@link #pixels()} once and then feeds dirty rows through {@link #flush} whenever
 * {@link #hasDirty()} is set; it only samples LOD 0, so the mirror is mip-less by design.
 *
 * <p>The backing store is a direct, native-order {@link ByteBuffer} allocated lazily on the first
 * write, so pure-VO worlds that never bake a model never pay the ~48 MB.</p>
 */
public final class ModelAtlasMirror {
    /** Cells are 48x32 RGBA texels: 3x2 of Voxy's 16x16 per-face model textures. */
    public static final int CELL_W = 48, CELL_H = 32;
    /** 128x64 cells = 8192 model slots; vanilla worlds bake far fewer. */
    public static final int ATLAS_COLS = 128, ATLAS_ROWS = 64;

    /** Full mirror dimensions in texels. */
    public static final int ATLAS_W = ATLAS_COLS * CELL_W;
    public static final int ATLAS_H = ATLAS_ROWS * CELL_H;

    private static ByteBuffer pixels;
    private static long pixelsAddr;

    // Dirty tracking is kept per atlas row: rows are the only granularity at which the renderpearl
    // encoder can upload a sub-rect, because writeToTexture expects tightly packed rows while the
    // mirror has a full-atlas stride (ATLAS_W*4 bytes per row).
    private static final boolean[] DIRTY_ROW = new boolean[ATLAS_ROWS];
    private static final int[] DIRTY_ROW_MIN_X = new int[ATLAS_ROWS];
    private static final int[] DIRTY_ROW_MAX_X = new int[ATLAS_ROWS];

    private static boolean warnedOverflow;

    private ModelAtlasMirror() {
    }

    /**
     * Copies one model cell (48x32 RGBA) from {@code srcAddr} into slot {@code modelId}. Reads may
     * happen off-thread (Voxy's upload path runs on the render thread, but be safe), writes are
     * synchronized against {@link #flush}/{@link #hasDirty}.
     */
    public static synchronized void writeCell(final int modelId, final long srcAddr) {
        if (modelId < 0 || modelId >= ATLAS_COLS * ATLAS_ROWS) {
            if (!warnedOverflow) {
                warnedOverflow = true;
                com.metallumextra.MetallumExtra.LOGGER.warn(
                        "[Metallum Extra] Voxy baked model id {} is beyond the {} slot Metal model atlas, dropping cells",
                        modelId, ATLAS_COLS * ATLAS_ROWS);
            }
            return;
        }
        ensureAllocated();

        final int col = modelId % ATLAS_COLS;
        final int row = modelId / ATLAS_COLS;
        final int dstX = col * CELL_W;
        final int dstY = row * CELL_H;

        // Row-by-row: a cell is not contiguous in the atlas-wide stride.
        for (int y = 0; y < CELL_H; y++) {
            final long dst = pixelsAddr + ((long) (dstY + y) * ATLAS_W + dstX) * 4L;
            UnsafeUtil.memcpy(srcAddr + (long) y * CELL_W * 4L, dst, CELL_W * 4L);
        }

        DIRTY_ROW[row] = true;
        DIRTY_ROW_MIN_X[row] = Math.min(DIRTY_ROW_MIN_X[row], dstX);
        DIRTY_ROW_MAX_X[row] = Math.max(DIRTY_ROW_MAX_X[row], dstX + CELL_W - 1);
    }

    /** True when cells were written since the last {@link #flush}. */
    public static synchronized boolean hasDirty() {
        for (final boolean dirty : DIRTY_ROW) {
            if (dirty) {
                return true;
            }
        }
        return false;
    }

    /**
     * Uploads the dirty atlas rows into {@code texture} through the renderpearl command encoder and
     * clears the dirty state. One row is uploaded per encoder call because the ByteBuffer overload
     * of {@code writeToTexture} expects a tightly packed {@code width}-wide source while the mirror
     * rows are {@link #ATLAS_W} texels wide.
     */
    public static synchronized void flush(final GpuTexture texture) {
        if (pixels == null) {
            return;
        }
        final int savedPosition = pixels.position();
        try {
            final var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int row = 0; row < ATLAS_ROWS; row++) {
                if (!DIRTY_ROW[row]) {
                    continue;
                }
                final int minX = DIRTY_ROW_MIN_X[row];
                final int width = DIRTY_ROW_MAX_X[row] - minX + 1;
                pixels.position((row * ATLAS_W + minX) * 4);
                encoder.writeToTexture(texture, pixels, 0, 0, minX, row, width, 1);
                DIRTY_ROW[row] = false;
                DIRTY_ROW_MIN_X[row] = ATLAS_W;
                DIRTY_ROW_MAX_X[row] = -1;
            }
        } finally {
            pixels.clear();
        }
        // savedPosition is always 0 by contract (pixels() and our own usage keep it there), but be explicit.
        if (savedPosition != 0) {
            pixels.position(0);
        }
    }

    /** The full atlas, position 0, native order. Null until the first cell is written. */
    public static ByteBuffer pixels() {
        synchronized (ModelAtlasMirror.class) {
            ensureAllocated();
            pixels.clear();
            return pixels;
        }
    }

    /** Clears the dirty state but keeps the pixels; baked models persist for the game session. */
    public static synchronized void reset() {
        for (int row = 0; row < ATLAS_ROWS; row++) {
            DIRTY_ROW[row] = false;
            DIRTY_ROW_MIN_X[row] = ATLAS_W;
            DIRTY_ROW_MAX_X[row] = -1;
        }
    }

    private static void ensureAllocated() {
        if (pixels == null) {
            pixels = MemoryUtil.memAlloc(ATLAS_W * ATLAS_H * 4).order(ByteOrder.nativeOrder());
            pixelsAddr = MemoryUtil.memAddress(pixels);
        }
    }
}
