#version 330

/*
 * Voxy LOD terrain, fragment stage: a port of voxy/shaders/lod/gl46/quads.frag. The atlas is this
 * mod's mirror of Voxy's baked model atlas (128 x 64 cells of 48 x 32 texels, mip-less), which is
 * the only difference from Voxy's own sampling: same cells, same per-face tiles, no mip levels.
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

uniform sampler2D u_Atlas;

layout(location = 0) flat in uint v_Flags;
layout(location = 1) flat in uint v_Tint;
layout(location = 2) flat in vec3 v_Shade;
layout(location = 3) in vec3 v_Point;
layout(location = 4) in vec2 v_UV;

layout(location = 0) out vec4 fragColour;

const vec2 ATLAS_SIZE = vec2(6144.0, 2048.0);

void main() {
    // Voxy's tile test: the quad's UVs run over its own grid of cells, and anything that has
    // interpolated past that grid is a neighbour's texel and is thrown away.
    vec2 quadSize = vec2(float((v_Flags >> 8) & 15u) + 1.0, float((v_Flags >> 12) & 15u) + 1.0);
    vec2 tile = floor(v_UV);
    if (any(notEqual(clamp(tile, vec2(0.0), quadSize - 1.0), tile))) discard;

    uint face = (v_Flags >> 4) & 7u;
    uint modelId = v_Flags >> 16;
    // The mirror lays cells out on a 128 wide grid rather than Voxy's square one, and each cell is
    // 48 x 32 texels: three columns of face tiles by two rows.
    vec2 cell = vec2(float(modelId % 128u), float(modelId / 128u)) * vec2(48.0, 32.0);
    vec2 faceTile = vec2(float(face >> 1), float(face & 1u)) * 16.0;
    // Clamped to texel centres so the sample can never reach into the next cell or face tile.
    vec2 texels = cell + faceTile + clamp((v_UV - tile) * 16.0, vec2(0.5), vec2(15.5));
    vec2 texPos = texels / ATLAS_SIZE;

    vec4 colour = texture(u_Atlas, texPos);
    // Voxy re-samples at LOD 0 for both of these to get around mip levels; the mirror has none.
    if (((v_Flags & 1u) != 0u) && colour.a <= 0.1) discard;
    colour.a = 1.0;

    uint tintState = (v_Flags >> 2) & 3u;
    bool doTint = tintState == 2u;
    if (tintState == 1u) {
        // Partial tint: grey pixels of a model are the ones a biome colour is meant to tint.
        if (abs(colour.r - colour.g) < 0.02 && abs(colour.g - colour.b) < 0.02) doTint = true;
    }
    if (doTint) {
        colour.rgb *= vec3(float((v_Tint >> 24) & 0xFFu), float((v_Tint >> 16) & 0xFFu),
                float((v_Tint >> 8) & 0xFFu)) / 255.0;
    }
    colour.rgb *= v_Shade;

    // Environmental fog from voxy's blit, over the distance the game says the fog runs from and to.
    // Its cap sits below full fog, which is what gives the field its haze rather than a wall.
    float fog = 0.0;
    float fogSpan = u_FogParams.y - u_FogParams.x;
    if (fogSpan > 1.0) {
        float inv = 1.0 / fogSpan;
        fog = clamp(length(v_Point) * inv + (-u_FogParams.x * inv), 0.0, u_FogColor.a);
    }
    // And the fade at the edge of the field, over world x rather than view x as voxy does: v1 has
    // no blending, so the field fades into the fog colour and is dropped once it is gone.
    float fade = 0.0;
    if (u_FogParams.w > u_FogParams.z) {
        fade = clamp((length(v_Point.xz) - u_FogParams.z) / (u_FogParams.w - u_FogParams.z), 0.0, 1.0);
    }
    if (fade >= 1.0) discard;

    // Voxy multiplies by the fog colour's alpha, which vanilla always leaves at one, so the alpha
    // channel of the uniform carries the fog cap instead.
    fragColour = vec4(mix(colour.rgb, u_FogColor.rgb, max(fog, fade)), 1.0);
}
