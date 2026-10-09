#version 330

// Turns the game's depth into OpenGL depth (0 at the near plane, 1 at the far plane), which is what Iris shaders read.
// The game's depth is -A + B / distance for the third and fourth entries A and B of its projection, so the distance
// follows from it, and OpenGL depth from the distance.

layout(std140) uniform DepthParams {
    vec4 params; // x: A. y: B. z: near. w: far.
};

uniform sampler2D u_Depth;

in vec2 texcoord;
out vec4 fragColor;

void main() {
    float depth = texelFetch(u_Depth, ivec2(gl_FragCoord.xy), 0).r;
    float distance = params.y / (depth + params.x);
    float near = params.z;
    float far = params.w;
    float ndc = (far + near) / (far - near) - 2.0 * far * near / ((far - near) * distance);
    gl_FragDepth = clamp(0.5 + 0.5 * ndc, 0.0, 1.0);
    fragColor = vec4(0.0);
}
