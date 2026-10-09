// What the fragment stage of one of Iris's programs that draw terrain reads from Sodium's pipeline. Put into a program by
// IrisTerrain; the program's own declarations of these names are taken out.

#ifdef ALPHA_CUTOUT
#define alphaTestRef ALPHA_CUTOUT
#else
#define alphaTestRef 0.0
#endif
#define textureMatrix mat4(1.0)
