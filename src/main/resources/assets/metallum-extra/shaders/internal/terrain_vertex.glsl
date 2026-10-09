// Sodium's chunk mesh seen through the vertex inputs of Iris's programs that draw terrain (vaPosition, vaColor, ...).
// Put into a program by IrisTerrain; the program's own declarations of these names are taken out.

layout(std140) uniform u_Globals {
    mat4 u_ProjectionMatrix;
    mat4 u_ModelViewMatrix;
    vec4 u_FogColor;
    vec2 u_EnvironmentFog;
    vec2 u_RenderFog;
    vec2 u_TexelSize;
    vec2 u_TexCoordShrink;
    float u_FadePeriodInv;
    bool u_UseRGSS;
};

layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};

in uvec2 a_Position;
in vec4 a_Color;
in uvec2 a_TexCoord;
in uvec4 a_LightAndData;

// Where the vertex is inside its region, in blocks: three 20-bit numbers whose high and low halves are in separate words,
// covering -8..24 blocks around the section, and the section's own place in the region. Add chunkOffset (the region's
// place relative to the camera) for the position in the camera's world.
vec3 mx_va_position() {
    uvec3 high = (uvec3(a_Position.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    uvec3 low = (uvec3(a_Position.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    vec3 local = vec3((high << 10u) | low) * (32.0 / 1048576.0) - 8.0;
    uint section = a_LightAndData.w;
    vec3 origin = vec3((section >> 5u) & 7u, section & 3u, (section >> 2u) & 7u) * 16.0;
    return local + origin;
}

// The mesh keeps the kind of block (BlockTypes) in the color's alpha; the shader sees the plain color.
vec4 mx_va_color() {
    return vec4(a_Color.rgb, 1.0);
}

vec2 mx_va_uv0() {
    vec2 coordinate = vec2(a_TexCoord & 0x7FFFu) / 32768.0;
    vec2 direction = vec2(a_TexCoord >> 15u) * 2.0 - 1.0;
    return coordinate + direction * u_TexCoordShrink;
}

// The light map coordinate as the game has it: 0 to 240 for light levels 0 to 15 (the mesh adds 8).
ivec2 mx_va_uv2() {
    return ivec2(clamp(vec2(a_LightAndData.xy) - 8.0, 0.0, 240.0));
}

// The mesh has no normals; the direction each quad faces is kept in bits 3 to 5 of its material byte (see the mesh tagging in
// SodiumMeshBufferShaderMixin): 1 to 6 are +x, -x, +y, -y, +z, -z, and anything else (not along an axis) is up, as the game lights plants.
vec3 mx_va_normal() {
    uint face = (a_LightAndData.z >> 3u) & 7u;
    if (face == 1u) return vec3(1.0, 0.0, 0.0);
    if (face == 2u) return vec3(-1.0, 0.0, 0.0);
    if (face == 4u) return vec3(0.0, -1.0, 0.0);
    if (face == 5u) return vec3(0.0, 0.0, 1.0);
    if (face == 6u) return vec3(0.0, 0.0, -1.0);
    return vec3(0.0, 1.0, 0.0);
}

// x: the block's id from block.properties, which is not supported yet, so -1 as Iris sends for a block with no id.
// y: 1 for fluids, -1 for everything else.
vec2 mx_mc_entity() {
    uint type = uint(a_Color.a * 255.0 + 0.5);
    return vec2(-1.0, (type == 1u || type == 2u) ? 1.0 : -1.0);
}

#define vaPosition mx_va_position()
#define vaColor mx_va_color()
#define vaUV0 mx_va_uv0()
#define vaUV2 mx_va_uv2()
#define vaNormal mx_va_normal()
#define mc_Entity mx_mc_entity()
#define chunkOffset u_RegionOffset
#define textureMatrix mat4(1.0)
#ifdef ALPHA_CUTOUT
#define alphaTestRef ALPHA_CUTOUT
#else
#define alphaTestRef 0.0
#endif
