// Reading the shadow map. Needs uniforms.glsl.
//
// The map is OpenGL depth as Iris has it: 0 nearest the light, 1 furthest, and 1 where nothing was drawn.

uniform sampler2D shadowtex0;

#include "shadow_bend.glsl"
#include "noise.glsl"

// One smoothed lookup: the four texels around `uv` are each compared with `threshold`, and the four yes-or-no
// answers are blended by how close `uv` is to each. Blending the answers, not the depths, is what makes a shadow
// edge slide smoothly across the map's texels as the sun turns, where a plain lookup would make it flicker from
// one texel to the next. Lit where the map's depth is not nearer the light than `threshold`.
float mx_shadow_tap(vec2 uv, float threshold) {
    ivec2 size = textureSize(shadowtex0, 0);
    vec2 position = uv * vec2(size) - 0.5;
    ivec2 corner = ivec2(floor(position));
    vec2 blend = position - vec2(corner);
    ivec2 last = size - 1;
    float a = step(threshold, texelFetch(shadowtex0, clamp(corner, ivec2(0), last), 0).r);
    float b = step(threshold, texelFetch(shadowtex0, clamp(corner + ivec2(1, 0), ivec2(0), last), 0).r);
    float c = step(threshold, texelFetch(shadowtex0, clamp(corner + ivec2(0, 1), ivec2(0), last), 0).r);
    float d = step(threshold, texelFetch(shadowtex0, clamp(corner + ivec2(1, 1), ivec2(0), last), 0).r);
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

// Where a point of the world (camera-relative) lands in the shadow map: x and y across it, 0 to 1 after the fish-eye bend, and z the depth, 0 to 1.
vec3 mx_shadow_coordinates(vec3 position) {
    vec4 clip = shadowProjection * shadowModelView * vec4(position, 1.0);
    return vec3(mx_shadow_bend(clip.xy) * 0.5 + 0.5, clip.z * 0.5 + 0.5);
}

// How much of the sun's or moon's light reaches a point: 1 fully lit, 0 fully shadowed.
// `position` is camera-relative world space; `normal` is the surface normal there.
float mx_shadow(vec3 position, vec3 normal) {
    float strength = MxLightDir.w;
    if (strength <= 0.0) return 1.0;

    float range = shadowDistance;
    float distance_from_camera = length(position);
    float beyond = smoothstep(range * 0.80, range * 0.97, distance_from_camera);
    if (beyond >= 1.0) return 1.0;
    float facing = clamp(dot(normal, MxLightDir.xyz), 0.0, 1.0);

    // Look the point up a little way off the surface, or the surface shadows itself in stripes. A texel covers
    // more ground further from the player and when the light grazes the surface, so step further off there.
    float squeeze = (distance_from_camera / range) * MX_SHADOW_BEND + (1.0 - MX_SHADOW_BEND);
    float texel = 1.0 / float(shadowMapResolution);
    float texel_size = 2.0 * range * texel * squeeze;
    vec3 lifted = position + normal * texel_size * (1.5 + 2.5 * (1.0 - facing));

    vec3 at = mx_shadow_coordinates(lifted);
#ifdef PIXEL_LOCKED_SHADOWS
    // Anchor each receiver to a shadow-map texel before filtering. This keeps the sampling footprint fixed
    // as the camera moves through a texel; the existing comparison filter still softens the edge.
    float resolution = float(shadowMapResolution) / float(shadowPixelSize);
    at.xy = (floor(at.xy * resolution) + vec2(0.5)) / resolution;
#endif
    float threshold = at.z - 0.00035;

    // Four smoothed lookups in a square give a soft edge about three texels wide, with no grain.
    float reach = texel * 0.75;
    float lit = mx_shadow_tap(at.xy + vec2(-reach, -reach), threshold)
              + mx_shadow_tap(at.xy + vec2(reach, -reach), threshold)
              + mx_shadow_tap(at.xy + vec2(-reach, reach), threshold)
              + mx_shadow_tap(at.xy + vec2(reach, reach), threshold);
    lit *= 0.25;

    return mix(1.0, mix(lit, 1.0, beyond), strength);
}
