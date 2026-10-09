#version 330

#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex6;

in vec2 texcoord;
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 fragColor;

// The blurred light, rolled off again the way composite3 undid it, laid over the world image as a thin layer: each pixel becomes mostly
// itself and a little of the light around it. Where the picture is even that changes nothing; next to something bright, the brightness spills over.
void main() {
    vec4 world = texture(colortex0, texcoord);
    // Five levels were added together on the way up.
    vec3 light = texture(colortex6, texcoord).rgb * 0.2;
    float level = max(light.r, max(light.g, light.b));
    vec3 shown = light * ((1.0 - exp(-level)) / max(level, 1.0e-4));
    float share = 0.18 * MxFlags.y;
    fragColor = vec4(mx_to_display(shown) * share + world.rgb * (1.0 - share), world.a);
}
