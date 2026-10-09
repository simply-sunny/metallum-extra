// Terrain: the vertex stage of gbuffers_terrain and gbuffers_water.

#include "common.glsl"

in vec3 vaPosition;
in vec4 vaColor;
in vec2 vaUV0;
in ivec2 vaUV2;
in vec2 mc_Entity;
in vec2 mc_midTexCoord;
in float mc_chunkFade;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform vec3 chunkOffset;

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 v_Light;
out vec3 v_Position;
out float v_Fade;
flat out uint v_Type;

void main() {
    uint type = uint(max(mc_Entity.x, 0.0) + 0.5);
    // Camera-relative world space: the model-view matrix only turns it to face where the camera looks.
    vec3 position = mx_wave(vaPosition + chunkOffset, type, vaUV0.y > mc_midTexCoord.y);

    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);

    // A newly loaded section fades in from the fog over a short time.
    v_Fade = mc_chunkFade;
    v_Color = vaColor;
    v_TexCoord = vaUV0;
    v_Light = clamp(vec2(vaUV2) / 240.0, 0.0, 1.0);
    v_Position = position;
    v_Type = type;
}
