// Particles, rain and snow.

#include "common.glsl"

in vec3 vaPosition;
in vec4 vaColor;
in vec2 vaUV0;
in ivec2 vaUV2;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;

out vec2 v_TexCoord;
out vec3 v_Position;
out vec2 v_Light;
out vec4 v_Color;

void main() {
    vec4 view = modelViewMatrix * vec4(vaPosition, 1.0);
    v_Position = mat3(gbufferModelViewInverse) * view.xyz;
    v_Light = clamp(vec2(vaUV2) / 240.0, 0.0, 1.0);
    v_Color = vaColor;
    v_TexCoord = vaUV0;
    gl_Position = projectionMatrix * view;
}
