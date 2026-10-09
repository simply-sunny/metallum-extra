#version 330 core

// Terrain, mobs, items and moving blocks as the sun or moon sees them, for the shadow map.

#include "/lib/common.glsl"
#include "/lib/shadow_bend.glsl"

in vec3 vaPosition;
in vec2 vaUV0;
in vec4 vaColor;
in vec2 mc_Entity;
in vec2 mc_midTexCoord;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform vec3 chunkOffset;

out vec2 v_TexCoord;
out float v_Alpha;

void main() {
    uint type = uint(max(mc_Entity.x, 0.0) + 0.5);
    vec3 position = mx_wave(vaPosition + chunkOffset, type, vaUV0.y > mc_midTexCoord.y);
    vec4 clip = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    clip.xy = mx_shadow_bend(clip.xy);
    // Anything nearer the light than the map reaches is flattened onto its near side instead of being cut off:
    // it still has to cast its shadow.
    clip.z = max(clip.z, -0.9999);
    gl_Position = clip;
    v_TexCoord = vaUV0;
    v_Alpha = vaColor.a;
}
