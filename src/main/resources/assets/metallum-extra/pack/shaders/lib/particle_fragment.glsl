// Particles, rain and snow.

#include "lit.glsl"

uniform sampler2D gtexture;

in vec2 v_TexCoord;
in vec3 v_Position;
in vec2 v_Light;
in vec4 v_Color;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(gtexture, v_TexCoord) * v_Color;
    if (color.a < 0.1) {
        discard;
    }
    // A particle always faces the camera, so it has no side to turn to the light: light it as if it faced
    // halfway between straight up and the sun.
    vec3 normal = normalize(MxLightDir.xyz + vec3(0.0, 1.0, 0.0));
    float facing = mx_facing(normal, 0.0);
    vec3 lit = mx_lit(mx_to_linear(color.rgb), normal, v_Position, v_Light, facing, facing > 0.0 ? mx_shadow(v_Position, normal) : 1.0);
    float fog = mx_fog_amount(v_Position, MxFog.xy, MxFog.zw);
    fragColor = vec4(mx_fogged(lit, v_Position, fog, MxFogColor), color.a);
}
