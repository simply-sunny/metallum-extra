// Sodium's chunk mesh seen through the vertex inputs of Iris's programs that draw terrain (vaPosition, vaColor, ...).
// Put into a program by IrisTerrain; the program's own declarations of these names are taken out.

#ifndef MX_SHADOW
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
#endif

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

// Sodium's mesh does not have the center of each quad's texture; it has which side of that center each vertex is on. So the
// center given here is only good for telling which side a vertex is on: it is a hair away from the vertex, towards the center.
vec2 mx_mid_tex_coord(vec2 uv) {
    vec2 direction = vec2(a_TexCoord >> 15u) * 2.0 - 1.0;
    return uv + direction * 0.0005;
}

vec2 mx_va_uv0() {
    vec2 coordinate = vec2(a_TexCoord & 0x7FFFu) / 32768.0;
    vec2 direction = vec2(a_TexCoord >> 15u) * 2.0 - 1.0;
#ifdef MX_SHADOW
    return coordinate;
#else
    return coordinate + direction * u_TexCoordShrink;
#endif
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

// x: the block's id from block.properties, y: 1 for fluids and -1 for everything else.
#ifdef MX_BLOCK_IDS
// The mesh keeps a small number for the block's id (the color's alpha byte and bit 6 of the material byte); MxBlockIds, which every program that reads
// it has in its uniform block, turns that number into the id. 0 is "no id", which a program reads as -1 as in Iris.
vec2 mx_mc_entity() {
    uint index = uint(a_Color.a * 255.0 + 0.5) | (((a_LightAndData.z >> 6u) & 1u) << 8u);
    int id = MxBlockIds[index >> 2u][index & 3u];
    return vec2(index == 0u ? -1.0 : float(id), -1.0);
}
#else
vec2 mx_mc_entity() {
    uint type = uint(a_Color.a * 255.0 + 0.5);
#ifdef MX_BLOCK_TYPES
    return vec2(float(type), (type == 1u || type == 2u) ? 1.0 : -1.0);
#else
    return vec2(-1.0, (type == 1u || type == 2u) ? 1.0 : -1.0);
#endif
}
#endif

#ifndef MX_SHADOW
// How far a newly built section has come in fading from the fog (0 just built, 1 done): Sodium's own fade-in.
uniform isamplerBuffer u_SectionTimeInfo;
float mx_chunk_fade() {
    int loaded_at = texelFetch(u_SectionTimeInfo, int(u_RegionID * 256u + a_LightAndData.w)).r;
    return loaded_at < 0 ? 1.0 : clamp(float(u_CurrentTime - loaded_at) * u_FadePeriodInv, 0.0, 1.0);
}
#endif

// The direction a quad's texture runs in (u increasing) is kept in four spare bits of the vertex position (see SodiumMeshBufferShaderMixin), as one of
// these directions; the side of the handedness (which way v runs, given the normal) is bit 7 of the material byte.
const vec3 MX_TANGENTS[16] = vec3[16](vec3(1.0, 0.0, 0.0), vec3(-1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0), vec3(0.0, 0.0, 1.0), vec3(0.0, 0.0, -1.0),
        vec3(0.7071068, 0.0, 0.7071068), vec3(0.7071068, 0.0, -0.7071068), vec3(-0.7071068, 0.0, 0.7071068), vec3(-0.7071068, 0.0, -0.7071068),
        vec3(1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0));

vec4 mx_at_tangent() {
    uint code = (a_Position.x >> 30u) | ((a_Position.y >> 30u) << 2u);
    float handedness = ((a_LightAndData.z >> 7u) & 1u) == 1u ? -1.0 : 1.0;
    return vec4(MX_TANGENTS[code], handedness);
}

// Where the vertex is from the middle of its block, in 1/64 of a block (-32 to 32). The mesh does not say which block a vertex belongs to, and a vertex
// on the edge of a block could be in either; the side of the quad's middle it is on (the same bits that give mid_tex_coord), turned into a direction with the
// tangent, settles it: the block is the one a hair towards the middle of the quad.
vec4 mx_mid_block() {
    vec4 tangent = mx_at_tangent();
    vec3 normal = mx_va_normal();
    vec2 toCenter = vec2(a_TexCoord >> 15u) * 2.0 - 1.0;
    vec3 inQuad = tangent.xyz * toCenter.x + cross(normal, tangent.xyz) * tangent.w * toCenter.y;
    vec3 position = mx_va_position();
    vec3 cell = floor(position + inQuad * 0.02);
    return vec4((position - cell - 0.5) * 64.0, 0.0);
}

#define at_tangent mx_at_tangent()
#define at_midBlock mx_mid_block()
#define vaPosition mx_va_position()
#define vaColor mx_va_color()
#define vaUV0 mx_va_uv0()
#define vaUV2 mx_va_uv2()
#define vaNormal mx_va_normal()
#define mc_Entity mx_mc_entity()
#define mc_midTexCoord mx_mid_tex_coord(mx_va_uv0())
#ifndef MX_SHADOW
#define mc_chunkFade mx_chunk_fade()
#else
#define mc_chunkFade 1.0
#endif
#define chunkOffset u_RegionOffset
#define textureMatrix mat4(1.0)
#ifdef ALPHA_CUTOUT
#define alphaTestRef ALPHA_CUTOUT
#else
#define alphaTestRef 0.0
#endif
