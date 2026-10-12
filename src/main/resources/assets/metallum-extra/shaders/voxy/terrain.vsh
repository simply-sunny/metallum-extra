#version 330

/*
 * Voxy LOD terrain, vertex stage: a port of voxy/shaders/lod/gl46/quads3.vert with quad_util.glsl,
 * pos_util.glsl and lighting.glsl inlined. The packed quad words arrive unchanged from
 * MetalVoxyVertexCapture, so every bit below is decoded exactly as Voxy decodes it.
 */
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform SceneUniform {
    mat4 u_MVP;
    ivec3 u_BaseSectionPos;
    uint u_FrameId;
    vec3 u_CameraSubPos;
    vec4 u_FogParams;
    vec4 u_FogColor;
    vec4 u_FaceTints;
};

// One node's packed position, pushed per draw: level in the high word, x/y/z around it.
layout(push_constant) uniform PC {
    uvec2 u_Node;
};

uniform sampler2D u_Lightmap;

layout(location = 0) in uvec2 a_Quad;
layout(location = 1) in uint a_FaceData;
layout(location = 2) in uint a_ModelFlags;
layout(location = 3) in uint a_Tint;

layout(location = 0) out flat uint v_Flags;
layout(location = 1) out flat uint v_Tint;
layout(location = 2) out flat vec3 v_Shade;
layout(location = 3) out vec3 v_Point;
layout(location = 4) out vec2 v_UV;

const int CORNERS[6] = int[6](0, 1, 2, 0, 2, 3);

uint getLoDLevel(uvec2 packedPos) {
    return packedPos.x >> 28;
}

ivec3 getLoDPosition(uvec2 packedPos) {
    int y = (int(packedPos.x) << 4) >> 24;
    int x = (int(packedPos.y) << 4) >> 8;
    int z = int((packedPos.x & ((1 << 20) - 1)) << 4);
    z |= int(packedPos.y >> 28);
    z <<= 8;
    z >>= 8;
    return ivec3(x, y, z);
}

// Voxy's swizzelDataAxis: which two axes a face's quad lives in (face >> 1 is 0 for X, 1 for Y, 2 for Z).
vec3 swizzelDataAxis(uint axis, vec3 data) {
    if (axis == 1) return data;
    return axis == 0 ? data.xzy : data.zxy;
}

// Voxy's getLightmapUv: an 8 bit light id, high nibble block light, low nibble sky light.
vec2 getLightmapUv(uint index) {
    vec2 base = vec2((index >> 4) & 0xF, index & 0xF) / 15.0;
    return clamp(base * (15.0 / 16.0) + (0.5 / 16.0), vec2(8.0 / 256.0), vec2(248.0 / 256.0));
}

// Voxy's directional face tint: each face takes the light the game gives that direction, and a model
// that opts out of shading is lit as if from above.
float getFaceTint(uint face, bool shaded) {
    if (!shaded) return u_FaceTints.y;
    if ((face >> 1) == 1) return u_FaceTints.z;
    if ((face >> 1) == 2) return u_FaceTints.w;
    if (face == 1) return u_FaceTints.y;
    return u_FaceTints.x;
}

void main() {
    uint lodLevel = getLoDLevel(u_Node);
    float lodScale = float(1u << lodLevel);
    // The node's origin, in the level-0 section grid and then in blocks, relative to the camera's section.
    ivec3 baseSection = (getLoDPosition(u_Node) << int(lodLevel)) - u_BaseSectionPos;

    uint face = a_Quad.x & 7;
    uint modelId = (a_Quad.x >> 26) | ((a_Quad.y & 0x3FFF) << 6);
    int x = int((a_Quad.x >> 21) & 31);
    int y = int((a_Quad.x >> 16) & 31);
    int z = int((a_Quad.x >> 11) & 31);
    vec2 quadSize = vec2(float((a_Quad.x >> 3) & 15) + 1.0, float((a_Quad.x >> 7) & 15) + 1.0);

    // Face offsets and sizes in cells, and how deep the quad sits in its face. The epsilon keeps
    // neighbouring quads overlapping by a hair, which is what stops cracks showing between them.
    vec4 faceSize = vec4(float(a_FaceData & 15), float((a_FaceData >> 4) & 15),
            float((a_FaceData >> 8) & 15), float((a_FaceData >> 12) & 15)) / 16.0;
    faceSize.xz -= vec2(0.00005);
    faceSize.yw -= faceSize.xz;
    float depthOffset = float((a_FaceData >> 16) & 15) / 16.0;

    vec3 quadStart = vec3(float(x), float(y), float(z));
    quadStart += swizzelDataAxis(face >> 1, vec3(faceSize.xz, mix(depthOffset, 1.0 - depthOffset, float(face & 1))));
    vec3 basePoint = quadStart * lodScale + vec3(baseSection << 5);

    vec2 quadSizeAddin = faceSize.yw + quadSize - 1.0;
    vec2 uvCorner = faceSize.xz;

    // The two triangles covering the quad, in the order the capture wrote its six corners; a corner
    // is (u, v) = ((c >> 1) & 1, c & 1), as voxy's indexed draw has it.
    int corner = CORNERS[gl_VertexIndex % 6];
    vec2 cornerUnit = vec2(float((corner >> 1) & 1), float(corner & 1));
    vec3 point = basePoint + swizzelDataAxis(face >> 1, vec3(quadSizeAddin * cornerUnit * lodScale, 0.0));

    v_Point = point - u_CameraSubPos;
    gl_Position = u_MVP * vec4(point, 1.0);
    // UVs are in cells (which are the model's 16x16 tiles), so they take no part in the LOD scaling.
    v_UV = uvCorner + quadSizeAddin * cornerUnit;

    // The bits of voxy's makeQuadFlags the fragment stage needs: model id, quad size, cutout, the
    // tint state and the face.
    uint flags = modelId << 16;
    flags |= uint((int(quadSize.x) - 1) << 8) | uint((int(quadSize.y) - 1) << 12);
    uint cutout = a_FaceData & 1;
    cutout |= uint(quadSize.x > 1.0 || quadSize.y > 1.0) & ((a_FaceData >> 23) & 1);
    flags |= cutout;
    flags |= ((a_FaceData >> 24) & 3) << 2;
    flags |= face << 4;
    v_Flags = flags;
    // Captured as RGBA already: voxy's shader would byte swap its ARGB here, but the capture did it.
    v_Tint = a_Tint;

    uint lightId = (a_Quad.y >> 23) & 0xFF;
    v_Shade = textureLod(u_Lightmap, getLightmapUv(lightId), 0.0).rgb * getFaceTint(face, (a_ModelFlags & 8) != 0);
}
