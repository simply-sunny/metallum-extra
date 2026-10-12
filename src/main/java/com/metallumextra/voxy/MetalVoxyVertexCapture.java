package com.metallumextra.voxy;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import org.lwjgl.system.MemoryUtil;

/**
 * Turns one of Voxy's built sections into the vertex records the Metal draw pass reads.
 *
 * <p>Voxy's meshing writes packed quads (8 bytes each, eight directional buckets, bucket 0 the
 * translucent one) and lets its GL backend bind the model and colour buffers as shader storage so
 * every vertex can look its model up. Here there is no shader storage: the lookup happens once on
 * the meshing worker that owns the result — model struct, biome colour LUT and the resolved tint
 * are read from the Stage-2 CPU mirrors and stored beside the quad — and each quad is expanded to
 * the six corners of its two triangles, so the draw needs no index buffer. The tint is frozen at
 * this moment: a colour LUT entry re-baked later only shows up when the section re-meshes.</p>
 *
 * <p>Records run {@code quadLo, quadHi, faceData, modelFlags, tintRGBA}, 20 bytes, native order;
 * the quad words keep the exact bits Voxy's {@code quad_format.glsl} decodes, so the vertex shader
 * ports that decode unchanged.</p>
 */
public final class MetalVoxyVertexCapture {
    /** quadLo, quadHi, faceData, model flags, tint — the four uint attributes plus the uvec2 quad. */
    public static final int VERTEX_STRIDE = 20;
    /** Corner ids of the two triangles covering a quad; corner (u,v) is {@code ((c>>1)&1, c&1)}. */
    private static final int[] CORNERS = {0, 1, 2, 0, 2, 3};
    /** {@code BlockModel.flagsA}: the colour field is a base index into the biome colour LUT, not a colour. */
    private static final int MODEL_FLAG_BIOME_LUT = 2;

    /** What the render thread takes over: the node's packed position, child mask and vertex bytes. */
    public record Result(long position, byte childExistence, long vertexAddress, int vertexCount) {
        /** True when the section had geometry (as opposed to an empty or data-less node). */
        public boolean hasGeometry() {
            return this.vertexAddress != 0L;
        }
    }

    /** CPU address of {@code ModelStore.modelBuffer}: 64-byte {@code BlockModel}s indexed by state id. */
    private final long modelBase;
    /** CPU address of {@code ModelStore.modelColourBuffer}: ARGB entries the biome LUT indexes. */
    private final long colourBase;
    /** Size of the colour buffer, so a stale base index can never read past it. */
    private final long colourLimit;

    public MetalVoxyVertexCapture(final long modelBase, final long colourBase, final long colourLimit) {
        this.modelBase = modelBase;
        this.colourBase = colourBase;
        this.colourLimit = colourLimit;
    }

    /**
     * Expands {@code section}'s opaque buckets (1..7; bucket 0 is translucent and the v1 draw does
     * not touch it) into a fresh off-heap vertex block. Called on a meshing worker thread, so it
     * allocates rather than reusing anything shared; the caller frees the block once it has been
     * uploaded ({@link #free(Result)}).
     */
    public Result capture(final BuiltSection section) {
        if (section.isEmpty() || section.geometryBuffer == null) {
            // No mesh at this node: the child mask still says whether there is anything below it.
            return new Result(section.position, section.childExistence, 0L, 0);
        }

        final long geometry = section.geometryBuffer.address;
        final long itemCountLong = section.geometryBuffer.size / 8;
        final int itemCount = (int) itemCountLong;
        final int[] offsets = section.offsets;
        if (offsets == null || offsets.length < 8) {
            return new Result(section.position, section.childExistence, 0L, 0);
        }

        long quadCount = 0;
        for (int bucket = 1; bucket < 8; bucket++) {
            final int from = offsets[bucket];
            final int to = bucket == 7 ? itemCount : offsets[bucket + 1];
            if (to > from) {
                quadCount += to - from;
            }
        }
        if (quadCount == 0) {
            return new Result(section.position, section.childExistence, 0L, 0);
        }

        final long address = MemoryUtil.nmemAlloc(quadCount * CORNERS.length * VERTEX_STRIDE);
        if (address == 0L) {
            throw new OutOfMemoryError("Could not allocate " + (quadCount * CORNERS.length * VERTEX_STRIDE) + " bytes for a Voxy section");
        }

        long destination = address;
        for (int bucket = 1; bucket < 8; bucket++) {
            final int from = offsets[bucket];
            final int to = bucket == 7 ? itemCount : offsets[bucket + 1];
            for (int i = from; i < to && i < itemCount; i++) {
                final long quad = MemoryUtil.memGetLong(geometry + (long) i * 8L);
                final int quadLo = (int) quad;
                final int quadHi = (int) (quad >>> 32);

                // The state id is the model id: 16 bits split across the two words (quad_format.glsl).
                final int modelId = (quadLo >>> 26) | ((quadHi & 0x3FFF) << 6);
                final int face = quadLo & 7;
                final long model = this.modelBase + (long) modelId * 64L;
                final int faceData = MemoryUtil.memGetInt(model + (long) face * 4L);
                final int modelFlags = MemoryUtil.memGetInt(model + 24L);
                final int tint = this.resolveTint(modelFlags, MemoryUtil.memGetInt(model + 28L), quadHi);

                for (final int corner : CORNERS) {
                    MemoryUtil.memPutInt(destination, quadLo);
                    MemoryUtil.memPutInt(destination + 4L, quadHi);
                    MemoryUtil.memPutInt(destination + 8L, faceData);
                    MemoryUtil.memPutInt(destination + 12L, modelFlags);
                    MemoryUtil.memPutInt(destination + 16L, tint);
                    destination += VERTEX_STRIDE;
                }
            }
        }

        return new Result(section.position, section.childExistence, address, (int) (quadCount * CORNERS.length));
    }

    /**
     * The per-vertex tint as RGBA, white when the model has none. A biome-dependent model keeps a
     * base index in its colour field and picks the row by the quad's biome id; a plain model holds
     * the ARGB colour itself, and Voxy writes {@code -1} for "no tint" — which as ARGB is opaque
     * white anyway, so both cases fall out of the same byte swap.
     */
    private int resolveTint(final int modelFlags, final int colourField, final int quadHi) {
        int argb = colourField;
        if ((modelFlags & MODEL_FLAG_BIOME_LUT) != 0) {
            final int biomeId = (quadHi >>> 14) & 0x1FF;
            final long index = Integer.toUnsignedLong(colourField) + biomeId;
            argb = index < this.colourLimit / 4
                    ? MemoryUtil.memGetInt(this.colourBase + index * 4L)
                    : -1;
        }
        final int red = (argb >> 16) & 0xFF;
        final int green = (argb >> 8) & 0xFF;
        final int blue = argb & 0xFF;
        final int alpha = (argb >>> 24) & 0xFF;
        return (red << 24) | (green << 16) | (blue << 8) | alpha;
    }

    /** Releases a result's vertex block; the render thread does this after uploading it. */
    public static void free(final Result result) {
        if (result.vertexAddress != 0L) {
            MemoryUtil.nmemFree(result.vertexAddress);
        }
    }
}
