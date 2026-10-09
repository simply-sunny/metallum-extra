#version 330

// Lays the effects over the world image: occlusion darkens, sun rays add light.

#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex5;

in vec2 texcoord;
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 effects = textureLod(colortex5, texcoord, 0.0);
    vec3 color = textureLod(colortex0, texcoord, 0.0).rgb;
    vec3 linear = mx_to_linear(color) * effects.a;
    vec3 rays = 1.0 - exp(-effects.rgb * MX_EXPOSURE);
    // Rays are screen-blended, so they brighten without clipping; in linear light, so they lift what is dark
    // by no more than they add to what is bright.
    fragColor = vec4(mx_to_display(linear + rays * (1.0 - linear)), 1.0);
}
