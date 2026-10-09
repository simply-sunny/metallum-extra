// What the programs of this pack read from the game. The first group is Iris's own. The Mx ones are not: they are the lighting model of this
// pack (the color of the sky and the light, and so on) as the mod works it out each frame, and a pack that reads them runs only in this mod.

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
uniform float viewWidth;
uniform float viewHeight;
uniform int isEyeInWater;

uniform vec4 MxLightDir;     // xyz: towards the sun or moon, whichever is lighting the world. w: shadow strength
uniform vec4 MxLightColor;   // rgb: linear color of that light. w: 1 by day, 0 by night
uniform vec4 MxSunDir;       // xyz: towards the sun. w: how much of it is above the horizon
uniform vec4 MxMoonDir;      // xyz: towards the moon. w: how much of it is above the horizon
uniform vec4 MxSkyAmbient;   // rgb: linear light from the open sky. w: rain
uniform vec4 MxBlockLight;   // rgb: linear color of block light at full strength. w: torch flicker
uniform vec4 MxMinAmbient;   // rgb: linear light that reaches everywhere. w: darkness effect
uniform vec4 MxSkyZenith;    // rgb: linear sky color overhead. w: star brightness
uniform vec4 MxSkyHorizon;   // rgb: linear sky color at the horizon. w: 1 = fog fades to the game's fog color
uniform vec4 MxSunsetColor;  // rgb: linear sunrise or sunset tint. a: strength
uniform vec4 MxLightGrid;    // xyz: where the light-color grid starts (camera-relative). w: one cell in blocks
uniform float MxEyeSky;      // sky light where the camera is, 0 to 1, smoothed over time
uniform vec4 MxFog;          // x, y: where the fog of the surroundings starts and ends. z, w: the same for the fog at the edge of the loaded world
uniform vec4 MxFogEnds;      // y: where the clouds fade out
uniform vec4 MxFogColor;     // the game's fog color, and how strongly it is applied (a)
uniform vec4 MxFlags;        // x: 1 where the dimension has a sun and moon. y: how much glow there is (more in a dimension with no sun)

#define MX_EXPOSURE 1.0
#define MX_PIXEL vec2(1.0 / viewWidth, 1.0 / viewHeight)
#define MX_SCREEN vec2(viewWidth, viewHeight)

// Where the camera is, wrapped so that noise of the position stays precise far from the origin.
vec3 mx_camera() {
    return mod(cameraPosition, 4096.0);
}

#ifdef WAVING
#define MX_WAVING true
#else
#define MX_WAVING false
#endif
#ifdef WATER_REFLECTIONS
#define MX_REFLECTIONS true
#else
#define MX_REFLECTIONS false
#endif
#ifdef COLORED_LIGHT
#define MX_COLORED_LIGHT true
#else
#define MX_COLORED_LIGHT false
#endif
