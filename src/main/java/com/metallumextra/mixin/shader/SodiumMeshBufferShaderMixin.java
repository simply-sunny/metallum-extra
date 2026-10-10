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

    /** The directions a quad's tangent is rounded to (the table of {@code mx_at_tangent} in terrain_vertex.glsl). */
    @org.spongepowered.asm.mixin.Unique
    private static final float[][] TANGENTS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
            {0.7071068F, 0, 0.7071068F}, {0.7071068F, 0, -0.7071068F}, {-0.7071068F, 0, 0.7071068F}, {-0.7071068F, 0, -0.7071068F}};

    /**
     * The direction along the quad in which its texture's u grows, as the code of the nearest of {@link #TANGENTS} (10 when the quad has no usable texture
     * mapping), and in bit 4 whether the quad is mirrored: whether v grows against {@code cross(normal, tangent)}.
     */
    @org.spongepowered.asm.mixin.Unique
    private static int metallumExtra$tangent(final ChunkVertexEncoder.Vertex[] v) {
        float e1x = v[1].x - v[0].x, e1y = v[1].y - v[0].y, e1z = v[1].z - v[0].z;
        float e2x = v[2].x - v[0].x, e2y = v[2].y - v[0].y, e2z = v[2].z - v[0].z;
        float du1 = v[1].u - v[0].u, dv1 = v[1].v - v[0].v, du2 = v[2].u - v[0].u, dv2 = v[2].v - v[0].v;
        float det = du1 * dv2 - du2 * dv1;
        if (Math.abs(det) < 1.0e-12F) return 10;
        float r = 1.0F / det;
        float tx = (e1x * dv2 - e2x * dv1) * r, ty = (e1y * dv2 - e2y * dv1) * r, tz = (e1z * dv2 - e2z * dv1) * r;
        float bx = (e2x * du1 - e1x * du2) * r, by = (e2y * du1 - e1y * du2) * r, bz = (e2z * du1 - e1z * du2) * r;
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length < 1.0e-9F) return 10;
        tx /= length;
        ty /= length;
        tz /= length;
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0e-9F) return 10;
        nx /= nl;
        ny /= nl;
        nz /= nl;
        // cross(normal, tangent) . bitangent: negative when the texture is mirrored.
        float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
        int mirrored = cx * bx + cy * by + cz * bz < 0.0F ? 1 : 0;
        int best = 10;
        float bestDot = 0.9F;
        for (int i = 0; i < TANGENTS.length; i++) {
            float dot = tx * TANGENTS[i][0] + ty * TANGENTS[i][1] + tz * TANGENTS[i][2];
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best | mirrored << 4;
    }

    @Redirect(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder;write(JI[Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J"))
    private long metallumExtra$tagQuad(final ChunkVertexEncoder encoder, final long pointer, final int materialBits, final ChunkVertexEncoder.Vertex[] vertices, final int section) {
        int material = materialBits;
        if (Shaders.active()) {
            int type = BlockTypes.current();
            int alpha = (type & 0xFF) << 24;
            // A palette index (see BlockIds) has a ninth bit, which rides in bit 6 of the material byte.
            material |= ((type >> 8) & 1) << 6;
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                vertex.color = (vertex.color & 0x00FFFFFF) | alpha;
            }
            // The mesh has no normals; the direction the quad faces rides in the unused bits 3 to 5 of its material byte.
            material |= metallumExtra$face(vertices) << 3;
            // The tangent's side is bit 7 of the material byte, its direction four spare bits of the position (the top two of each of its words).
            int tangent = metallumExtra$tangent(vertices);
            material |= (tangent >> 4) << 7;
            long next = encoder.write(pointer, material, vertices, section);
            for (int i = 0; i < vertices.length; i++) {
                long vertex = pointer + 20L * i;
                org.lwjgl.system.MemoryUtil.memPutInt(vertex, org.lwjgl.system.MemoryUtil.memGetInt(vertex) | ((tangent & 3) << 30));
                org.lwjgl.system.MemoryUtil.memPutInt(vertex + 4, org.lwjgl.system.MemoryUtil.memGetInt(vertex + 4) | (((tangent >> 2) & 3) << 30));
            }
            return next;
        }
        return encoder.write(pointer, material, vertices, section);
    }
}
