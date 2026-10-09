#version 330

// Softens the half-size effects image without smearing across depth edges.

#include "/lib/common.glsl"

uniform sampler2D colortex4;
uniform sampler2D depthtex0;

const int colortex5Format = RGBA16F;

in vec2 texcoord;
/* RENDERTARGETS: 5 */
layout(location = 0) out vec4 blurred;

// The depth an effects pixel was worked out from: the first of the four depth texels under it.
float linear_depth(ivec2 pixel) {
    ivec2 texel = clamp(pixel * 2, ivec2(0), textureSize(depthtex0, 0) - 1);
    float depth = texelFetch(depthtex0, texel, 0).r;
    if (depth >= 1.0) {
        return 1.0e5;
    }
    vec4 view = gbufferProjectionInverse * vec4((vec2(texel) + 0.5) * MX_PIXEL * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return -view.z / view.w;
}

void main() {
    vec2 texel = 1.0 / vec2(textureSize(colortex4, 0));
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float here = linear_depth(pixel);
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec2 at = texcoord + vec2(x, y) * texel;
            float there = linear_depth(pixel + ivec2(x, y));
            float weight = exp(-abs(there - here) / (0.03 * here + 0.15)) * exp(-float(x * x + y * y) * 0.15);
            sum += textureLod(colortex4, at, 0.0) * weight;
            weights += weight;
        }
    }
    blurred = sum / max(weights, 1.0e-4);
}
