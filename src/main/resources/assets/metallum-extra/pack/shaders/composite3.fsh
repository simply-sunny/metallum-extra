#version 330

// Glow around the brightest parts of the finished world image: the image at half size, with its light as bright as it was before being
// rolled off towards white. (composite4 to composite7 shrink it four more times, composite8 to composite11 grow it back level by level,
// and composite12 lays a thin layer of it over the world.)

#include "/lib/common.glsl"

uniform sampler2D colortex0;

const int colortex6Format = RGBA16F;

in vec2 texcoord;
/* RENDERTARGETS: 6 */
layout(location = 0) out vec4 glow;

// The world image holds light after it has been rolled off towards white (see color.glsl), so in it the sun is
// no brighter than a white wall. Undoing the roll-off gives back how bright each thing was: the sun, lava and a
// torch flame then far outweigh what is around them when the image is blurred, and it is they that glow.
vec3 scene_light(vec2 uv) {
    vec3 shown = mx_to_linear(texture(colortex0, uv).rgb);
    float level = max(shown.r, max(shown.g, shown.b));
    return shown * (-log(1.0 - min(level, 0.996)) / max(level, 1.0e-4));
}

// World image -> half size. Four spread samples per pixel, each weighted down by its own brightness, so a
// single very bright pixel does not flicker as the camera moves.
void main() {
    vec2 texel = 1.0 / vec2(textureSize(colortex0, 0));
    vec3 a = scene_light(texcoord + texel * vec2(-1.0, -1.0));
    vec3 b = scene_light(texcoord + texel * vec2(1.0, -1.0));
    vec3 c = scene_light(texcoord + texel * vec2(-1.0, 1.0));
    vec3 d = scene_light(texcoord + texel * vec2(1.0, 1.0));
    float wa = 1.0 / (1.0 + mx_luminance(a));
    float wb = 1.0 / (1.0 + mx_luminance(b));
    float wc = 1.0 / (1.0 + mx_luminance(c));
    float wd = 1.0 / (1.0 + mx_luminance(d));
    glow = vec4((a * wa + b * wb + c * wc + d * wd) / (wa + wb + wc + wd), 1.0);
}
