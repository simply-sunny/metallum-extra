#version 330 core

// The sky is drawn over the whole screen in place of the game's sky disc: see gbuffers_skybasic.fsh.

#include "/lib/common.glsl"

in vec3 vaPosition;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;

void main() {
    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
}
