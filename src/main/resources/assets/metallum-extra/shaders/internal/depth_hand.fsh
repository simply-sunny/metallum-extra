#version 330
#extension GL_ARB_separate_shader_objects : require

// The held item and arm are drawn after the world, into a depth buffer that was cleared for them. Where they are, this turns their depth into what a shader
// pack expects of a hand: OpenGL depth, squeezed towards the near plane (the clip depth is scaled by MC_HAND_DEPTH), which is how Iris draws it. A pack tells hand
// from world by that (anything below 0.56 is the hand) and undoes the squeeze to find where the hand is.

layout(std140) uniform DepthParams {
    vec4 params; // x: A. y: B. z: near. w: far.
};

uniform sampler2D u_Depth;

layout(location = 0) in vec2 texcoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texelFetch(u_Depth, ivec2(gl_FragCoord.xy), 0).r;
    // Nothing was drawn here: the buffer still has what it was cleared to.
    if (depth <= 0.0) discard;
    float distance = params.y / (depth + params.x);
    float near = params.z;
    float far = params.w;
    float ndc = (far + near) / (far - near) - 2.0 * far * near / ((far - near) * distance);
    gl_FragDepth = clamp(0.5 + 0.5 * ndc * 0.125, 0.0, 1.0);
    fragColor = vec4(0.0);
}
