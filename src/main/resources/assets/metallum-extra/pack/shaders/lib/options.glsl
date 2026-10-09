// The options of the built-in shader pack, found by the loader in the Iris way: a line with a list of values after it is a choice (the
// value in the line is the default), and a plain #define, or one commented out, is an on-or-off switch. The names the player sees are in
// lang/en_us.lang.

#define SHADOWS
#define PIXEL_LOCKED_SHADOWS
const int shadowPixelSize = 1; // [1 2 3 4 6 8]
const int shadowMapResolution = 2048; // [512 1024 2048 4096 8192]
const float shadowDistance = 96.0; // [32.0 48.0 64.0 96.0 128.0 192.0 256.0 384.0 512.0]
const float sunPathRotation = 0.0; // [-60.0 -45.0 -30.0 -15.0 0.0 15.0 30.0 45.0 60.0]
#define AMBIENT_OCCLUSION
//#define SUN_RAYS
#define BLOOM
#define SMOOTH_EDGES
#define COLORED_LIGHT
#define WAVING
#define WATER_REFLECTIONS
