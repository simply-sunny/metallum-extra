#version 330
#extension GL_ARB_separate_shader_objects : require

// Draws one image into another of a different format.

uniform sampler2D colortex0;

layout(location = 0) in vec2 texcoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0);
}
