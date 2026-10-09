#version 330 core

#include "/lib/lit.glsl"

uniform sampler2D gtexture;
uniform float alphaTestRef;

in vec2 v_TexCoord;
in vec3 v_Position;
in vec2 v_Light;
in vec4 v_Color;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(gtexture, v_TexCoord) * v_Color;
    if (color.a < alphaTestRef) {
        discard;
    }
    // These meshes carry no normals; every face is flat, so one is worked out from the surface itself.
    vec3 normal = normalize(cross(dFdx(v_Position), dFdy(v_Position)));
    if (dot(normal, v_Position) > 0.0) {
        normal = -normal;
    }
    float facing = mx_facing(normal, 0.0);
    vec3 lit = mx_lit(mx_to_linear(color.rgb), normal, v_Position, v_Light, facing, facing > 0.0 ? mx_shadow(v_Position, normal) : 1.0);
    float fog = mx_fog_amount(v_Position, MxFog.xy, MxFog.zw);
    fragColor = vec4(mx_fogged(lit, v_Position, fog, MxFogColor), color.a);
}
