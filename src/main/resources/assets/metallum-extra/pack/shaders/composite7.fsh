#version 330

#include "/lib/uniforms.glsl"

uniform sampler2D colortex9;
#define SOURCE colortex9

const int colortex10Format = RGBA16F;

in vec2 texcoord;
/* RENDERTARGETS: 10 */
layout(location = 0) out vec4 level;

// Halves the image again: the center plus four diagonal samples, each of which already averages four texels.
void main() {
    vec2 texel = 1.0 / vec2(textureSize(SOURCE, 0));
    vec3 sum = texture(SOURCE, texcoord).rgb * 4.0;
    sum += texture(SOURCE, texcoord + texel * vec2(-1.0, -1.0)).rgb;
    sum += texture(SOURCE, texcoord + texel * vec2(1.0, -1.0)).rgb;
    sum += texture(SOURCE, texcoord + texel * vec2(-1.0, 1.0)).rgb;
    sum += texture(SOURCE, texcoord + texel * vec2(1.0, 1.0)).rgb;
    level = vec4(sum / 8.0, 1.0);
}
