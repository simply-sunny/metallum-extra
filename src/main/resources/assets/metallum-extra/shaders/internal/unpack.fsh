#version 330
#extension GL_ARB_separate_shader_objects : require

// The buffers the world was drawn into packed together (four 8-bit buffers in the four 32-bit channels of one attachment), taken apart into the buffers themselves.
// COUNT is how many of the four channels hold a buffer.

uniform usampler2D packedBuffers;

layout(location = 0) in vec2 texcoord;
layout(location = 0) out vec4 out0;
#if COUNT > 1
layout(location = 1) out vec4 out1;
#endif
#if COUNT > 2
layout(location = 2) out vec4 out2;
#endif
#if COUNT > 3
layout(location = 3) out vec4 out3;
#endif

vec4 unpack8(uint v) {
    return vec4(float(v & 255u), float((v >> 8) & 255u), float((v >> 16) & 255u), float(v >> 24)) / 255.0;
}

void main() {
    uvec4 p = texelFetch(packedBuffers, ivec2(gl_FragCoord.xy), 0);
    out0 = unpack8(p.x);
#if COUNT > 1
    out1 = unpack8(p.y);
#endif
#if COUNT > 2
    out2 = unpack8(p.z);
#endif
#if COUNT > 3
    out3 = unpack8(p.w);
#endif
}
