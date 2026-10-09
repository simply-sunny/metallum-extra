#version 330

// Smooths the stair steps along edges in the finished world image. Only where the picture's depth says one thing ends and another begins,
// or a surface turns a corner: the texels of a block's own texture are edges too, to the eye of a filter, and blending those would blur every block.

#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform float near;
uniform float far;

in vec2 texcoord;
layout(location = 0) out vec4 fragColor;

#ifdef SMOOTH_EDGES
// The edge test below was made for depth as the game stores it, which is the nearer the greater and, like OpenGL's, grows by the same
// amount from pixel to pixel across a flat surface. OpenGL depth is turned into that: proportional to one over the distance, and 0 for the sky.
float mx_old_depth(float depth) {
    if (depth >= 1.0) return 0.0;
    float distance_to = 2.0 * far * near / ((far + near) - (depth * 2.0 - 1.0) * (far - near));
    return 0.05 / distance_to - 4.883e-5;
}

float brightness(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

vec3 at(vec2 uv) {
    return textureLod(colortex0, uv, 0.0).rgb;
}

float depth_at(ivec2 texel) {
    return mx_old_depth(texelFetch(depthtex0, clamp(texel, ivec2(0), textureSize(depthtex0, 0) - 1), 0).r);
}

// Depth as stored changes by the same amount from each pixel to the next across a flat surface, however the
// surface is turned. Where it does not, the surface bends or ends.
bool on_edge() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float here = depth_at(texel);
    float left = depth_at(texel - ivec2(1, 0));
    float right = depth_at(texel + ivec2(1, 0));
    float down = depth_at(texel - ivec2(0, 1));
    float up = depth_at(texel + ivec2(0, 1));
    float bend = abs(left + right - 2.0 * here) + abs(down + up - 2.0 * here);
    float nearest = max(here, max(max(left, right), max(down, up)));
    return bend > nearest * 0.0015;
}

void main() {
    vec3 middle = at(texcoord);
    if (!on_edge()) {
        fragColor = vec4(middle, 1.0);
        return;
    }

    vec2 pixel = MX_PIXEL;
    float m = brightness(middle);
    float nw = brightness(at(texcoord + pixel * vec2(-1.0, -1.0)));
    float ne = brightness(at(texcoord + pixel * vec2(1.0, -1.0)));
    float sw = brightness(at(texcoord + pixel * vec2(-1.0, 1.0)));
    float se = brightness(at(texcoord + pixel * vec2(1.0, 1.0)));
    float lowest = min(m, min(min(nw, ne), min(sw, se)));
    float highest = max(m, max(max(nw, ne), max(sw, se)));

    // Too little difference to see steps in, more so where the picture is bright.
    if (highest - lowest < max(0.03, highest * 0.12)) {
        fragColor = vec4(middle, 1.0);
        return;
    }

    // The edge runs at right angles to the way brightness changes. The steeper it runs towards one axis the
    // further the blend reaches along it, up to eight pixels.
    vec2 along = vec2((sw + se) - (nw + ne), (nw + sw) - (ne + se));
    float least = max((nw + ne + sw + se) * 0.03125, 0.0078125);
    along = clamp(along / (min(abs(along.x), abs(along.y)) + least), vec2(-8.0), vec2(8.0)) * pixel;

    vec3 near = 0.5 * (at(texcoord - along / 6.0) + at(texcoord + along / 6.0));
    vec3 far = 0.5 * near + 0.25 * (at(texcoord - along * 0.5) + at(texcoord + along * 0.5));
    // The wider blend is taken unless it reached past the edge into something else.
    float reached = brightness(far);
    fragColor = vec4(reached < lowest || reached > highest ? near : far, 1.0);
}
#else
void main() {
    fragColor = vec4(textureLod(colortex0, texcoord, 0.0).rgb, 1.0);
}
#endif
