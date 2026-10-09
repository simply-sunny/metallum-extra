// Mobs, players, armor, block entities and items: the fragment stage of gbuffers_entities (and, with EYES defined, of gbuffers_spidereyes).

#include "lit.glsl"

uniform sampler2D gtexture;
uniform float alphaTestRef;
#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

in vec2 v_TexCoord;
in vec3 v_Position;
in vec3 v_Normal;
in vec2 v_Light;
in vec4 v_Color;
in vec4 v_Overlay;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(gtexture, v_TexCoord);
    if (texel.a < alphaTestRef) {
        discard;
    }

    vec4 color = texel * v_Color;
#ifdef DISSOLVE
    // A dissolving model shows or hides each texel outright; it is never see-through.
    if (v_Color.a < texture(DissolveMaskSampler, v_TexCoord).a) {
        discard;
    }
    color.a = 1.0;
#endif

#ifndef NO_OVERLAY
    color.rgb = mix(v_Overlay.rgb, color.rgb, v_Overlay.a);
#endif
    vec3 albedo = mx_to_linear(color.rgb);
#ifdef EYES
    // Glowing parts (eyes, charged creepers) give off their own light.
    vec3 lit = albedo * 1.7;
#else
    vec3 normal = normalize(v_Normal);
    if (dot(normal, v_Position) > 0.0) {
        normal = -normal;
    }
#ifdef NO_CARDINAL_LIGHTING
    // These are meant to look the same from every side.
    normal = normalize(MxLightDir.xyz * 0.5 + vec3(0.0, 1.0, 0.0));
#endif
    float facing = mx_facing(normal, 0.0);
    vec3 lit = mx_lit(albedo, normal, v_Position, v_Light, facing, facing > 0.0 ? mx_shadow(v_Position, normal) : 1.0);
#endif
    float fog = mx_fog_amount(v_Position, MxFog.xy, MxFog.zw);
    fragColor = vec4(mx_fogged(lit, v_Position, fog, MxFogColor), color.a);
}
