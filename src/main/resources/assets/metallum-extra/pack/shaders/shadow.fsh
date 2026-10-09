#version 330 core

#include "/lib/options.glsl"

uniform sampler2D gtexture;

in vec2 v_TexCoord;
in float v_Alpha;

layout(location = 0) out vec4 shadowColor;

void main() {
    // Leaves and plants cast shadows with holes in them.
    if (texture(gtexture, v_TexCoord).a * v_Alpha < 0.1) {
        discard;
    }
    shadowColor = vec4(1.0);
}
