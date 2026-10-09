#version 330 core

// The game's clouds, lit by the sun, moon and sky like everything else (see gbuffers_clouds.fsh); the mesh and where it is are the game's.

#include "/lib/common.glsl"

in vec3 vaPosition;
in vec4 vaColor;
in vec3 vaNormal;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;

out float v_Distance;
out vec4 v_Color;
out vec3 v_Position;
flat out vec3 v_Normal;

void main() {
    // Camera-relative world space: the model-view matrix only turns it to face where the camera looks.
    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
    v_Distance = length(vaPosition);
    v_Color = vaColor;
    v_Position = vaPosition;
    v_Normal = vaNormal;
}
