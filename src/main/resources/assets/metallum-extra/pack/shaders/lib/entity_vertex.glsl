// Mobs, players, armor, block entities and items: the vertex stage of gbuffers_entities, gbuffers_spidereyes and gbuffers_hand-less lighting.

#include "common.glsl"

in vec3 vaPosition;
in vec4 vaColor;
in vec2 vaUV0;
in ivec2 vaUV1;
in ivec2 vaUV2;
in vec3 vaNormal;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform mat4 textureMatrix;

out vec2 v_TexCoord;
out vec3 v_Position;
out vec3 v_Normal;
out vec2 v_Light;
out vec4 v_Color;
out vec4 v_Overlay;

void main() {
    vec4 view = modelViewMatrix * vec4(vaPosition, 1.0);
    // Camera-relative world space: the camera's turn is undone.
    v_Position = mat3(gbufferModelViewInverse) * view.xyz;
    v_Normal = mat3(gbufferModelViewInverse) * (mat3(modelViewMatrix) * vaNormal);
    v_Light = clamp(vec2(vaUV2) / 240.0, 0.0, 1.0);
    v_Color = vaColor;
    v_Overlay = mx_overlay_color(vaUV1);
    gl_Position = projectionMatrix * view;
#ifdef APPLY_TEXTURE_MATRIX
    v_TexCoord = (textureMatrix * vec4(vaUV0, 0.0, 1.0)).xy;
#else
    v_TexCoord = vaUV0;
#endif
}
