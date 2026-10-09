package com.metallumextra.mixin.shader;

import com.metallumextra.shader.BlockTypes;
import com.metallumextra.shader.Shaders;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Shaders: where Sodium packs a quad into the chunk mesh, the kind of block it came from is written into the
 * alpha byte of each vertex's color (see {@link BlockTypes}). Only while shaders are on: Sodium's own terrain
 * shader multiplies that alpha into the pixel, and the chunks are rebuilt whenever shaders are switched.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder", remap = false)
public abstract class SodiumMeshBufferShaderMixin {
    /**
     * Which way a quad faces, for the standard packs' {@code vaNormal}: 1 to 6 for +x, -x, +y, -y, +z, -z, and 0 for a quad that faces
     * none of them closely (plants and the like), which the shader treats as facing up. Quads wind counter-clockwise seen from the front.
     */
    @org.spongepowered.asm.mixin.Unique
    private static int metallumExtra$face(final ChunkVertexEncoder.Vertex[] v) {
        float ax = v[1].x - v[0].x, ay = v[1].y - v[0].y, az = v[1].z - v[0].z;
        float bx = v[2].x - v[0].x, by = v[2].y - v[0].y, bz = v[2].z - v[0].z;
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-6F) return 0;
        nx /= length;
        ny /= length;
        nz /= length;
        if (nx > 0.9F) return 1;
        if (nx < -0.9F) return 2;
        if (ny > 0.9F) return 3;
        if (ny < -0.9F) return 4;
        if (nz > 0.9F) return 5;
        if (nz < -0.9F) return 6;
        return 0;
    }

    @Redirect(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder;write(JI[Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J"))
    private long metallumExtra$tagQuad(final ChunkVertexEncoder encoder, final long pointer, final int materialBits, final ChunkVertexEncoder.Vertex[] vertices, final int section) {
        int material = materialBits;
        if (Shaders.active()) {
            int alpha = BlockTypes.current() << 24;
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                vertex.color = (vertex.color & 0x00FFFFFF) | alpha;
            }
            // The mesh has no normals; the direction the quad faces rides in the unused bits 3 to 5 of its material byte.
            material |= metallumExtra$face(vertices) << 3;
        }
        return encoder.write(pointer, material, vertices, section);
    }
}
