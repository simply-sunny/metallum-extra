#version 330

#include "/lib/uniforms.glsl"

uniform sampler2D colortex7;
uniform sampler2D colortex6;
#define SOURCE colortex7
#define BIGGER colortex6

const int colortex6Format = RGBA16F;

in vec2 texcoord;
/* RENDERTARGETS: 6 */
layout(location = 0) out vec4 sum_up;

// Grows the smaller level back up with a 3x3 tent, to be added onto the level above it.
void main() {
    vec2 texel = 1.0 / vec2(textureSize(SOURCE, 0));
    vec3 sum = texture(SOURCE, texcoord).rgb * 4.0;
    sum += texture(SOURCE, texcoord + texel * vec2(-1.0, 0.0)).rgb * 2.0;
    sum += texture(SOURCE, texcoord + texel * vec2(1.0, 0.0)).rgb * 2.0;
    sum += texture(SOURCE, texcoord + texel * vec2(0.0, -1.0)).rgb * 2.0;
    sum += texture(SOURCE, texcoord + texel * vec2(0.0, 1.0)).rgb * 2.0;
    sum += texture(SOURCE, texcoord + texel * vec2(-1.0, -1.0)).rgb;
    sum += texture(SOURCE, texcoord + texel * vec2(1.0, -1.0)).rgb;
    sum += texture(SOURCE, texcoord + texel * vec2(-1.0, 1.0)).rgb;
    sum += texture(SOURCE, texcoord + texel * vec2(1.0, 1.0)).rgb;
    sum_up = vec4(texture(BIGGER, texcoord).rgb + sum / 16.0, 1.0);
}
