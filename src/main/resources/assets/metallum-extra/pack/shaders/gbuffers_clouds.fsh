#version 330 core

#include "/lib/common.glsl"

in float v_Distance;
in vec4 v_Color;
in vec3 v_Position;
flat in vec3 v_Normal;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 fragColor;

void main() {
    // The game thins its clouds out with distance, up to where it stops drawing them.
    float thin = clamp(v_Distance / max(MxFogEnds.y, 1.0e-4), 0.0, 1.0);
    if (MxSkyHorizon.w > 0.5) {
        fragColor = vec4(v_Color.rgb, v_Color.a * (1.0 - thin));
        return;
    }

    vec3 normal = v_Normal;
    if (dot(normal, v_Position) > 0.0) {
        normal = -normal;
    }
    vec3 view = normalize(v_Position);

    // A cloud is a thick body of mist. Light goes round and through it, so the side turned from the sun is
    // dimmer but never dark, and its edge shines when the sun is close behind it.
    float towards = dot(normal, MxLightDir.xyz);
    float direct = 0.34 + 0.66 * clamp(towards * 0.5 + 0.5, 0.0, 1.0);
    float lining = pow(max(dot(view, MxLightDir.xyz), 0.0), 10.0);
    vec3 light = MxLightColor.rgb * (direct + 0.6 * lining)
               + MxSkyAmbient.rgb * (0.72 + 0.28 * normal.y)
               + MxMinAmbient.rgb;
    vec3 lit = vec3(0.50) * light * (1.0 - clamp(MxMinAmbient.w, 0.0, 0.85));
    // Rain clouds are a closed deck with no sunlit side: the color of the rainy sky, a shade darker.
    lit = mix(lit, MxSkyHorizon.rgb * 0.9, smoothstep(0.0, 0.7, MxSkyAmbient.w));

    // Far clouds sink into the haze of the sky behind them.
    float haze = 1.0 - exp(-v_Distance * 0.0009);
    lit = mix(lit, mx_sky(view), haze);

    fragColor = vec4(mx_encode(lit), v_Color.a * (1.0 - thin));
}
