#version 330

// Draws one image into another of a different format.

uniform sampler2D colortex0;

in vec2 texcoord;
out vec4 fragColor;

void main() {
    fragColor = texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0);
}
