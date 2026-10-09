# Making shader packs

A shader pack is a ZIP file of GLSL that replaces the look of Metallum Extra's shader pipeline. This page explains what a
pack is, how it is loaded, what your shaders can read, and how to work on one. For installing and choosing packs, see
"Shader packs" in [details.md](details.md).

Shader packs written for Iris or OptiFine do **not** work here. These packs are for Metallum Extra's own pipeline, which
runs on Metal (macOS, Apple Silicon) and needs Sodium.

## The short version

1. Take the built-in shaders (see "Starting point") and zip them up with a `pack.json`.
2. Edit the GLSL.
3. Drag the ZIP onto the shader menu (**I**), or put it in `shaderpacks/` in your game folder; choose it and press **Apply**.
4. After editing the ZIP again, press **R** (Reload Current Shaders) in game.

## What a pack looks like

```
MyPack.zip
├── pack.json
└── shaders/
    ├── override/
    │   ├── minecraft/core/   block, item, entity, particle, rendertype_clouds   (.vsh and .fsh)
    │   └── sodium/blocks/    block_layer_opaque                                  (.vsh and .fsh)
    ├── program/              this mod's own passes (see the table below)
    └── lib/                  anything your shaders #include
```

`pack.json` is `{"format": 1}`. Other fields are allowed and ignored. A different `format` number is refused, so an old
version of the mod never half-understands a newer pack. There is no author, version or ID field: the **name of the ZIP
file** (without `.zip`) is the pack's name in the menu.

## Options your pack offers

A pack can list settings in `pack.json`. They show up under **Shader Options... > "<your pack> Options..."**, are
saved per pack (in `config/metallum-extra-packs.properties`), and reach your shaders as `#define`s.

```json
{
  "format": 1,
  "options": [
    {"id": "QUALITY", "name": "Quality", "values": ["Low", "Medium", "High", "Ultra"], "default": "High"},
    {"id": "BLOOM", "name": "Bloom", "type": "toggle", "default": true},
    {"id": "GRAIN", "name": "Film Grain", "type": "toggle"}
  ]
}
```

- `id`: capital letters, digits and `_`, starting with a letter; the shaders see `OPTION_<id>`. Required, and unique.
- `name`: the label in the menu (the id if left out).
- A **choice** (the default type) has `values`, 2 to 16 texts, and an optional `default` that is one of them (the first
  if left out). The define is the value's position, counting from 0: `OPTION_QUALITY` is 2 for `High`.
- A **toggle** (`"type": "toggle"`) is Off (0) or On (1); `default` is `true` or `false` (false if left out).
- Up to 64 options; 12 are shown per page.

The mod puts the defines right after the first line of every shader of the pack, which is why that line must be the
`#version`. Use them like any other define:

```glsl
#if OPTION_QUALITY >= 2
    // shadows with more samples
#endif
#if OPTION_BLOOM == 1
    color += glow;
#endif
```

Changes are saved as they are made, and the shaders are compiled again when the player leaves the options screen. Every
shader is read again with the new defines, so the first change to a value you have not used before takes a moment; the
Metal translations of the results are cached, so going back is instant. A pack with a mistake in an option in
`pack.json` is shown in the menu with the reason and cannot be chosen.

This is also how one pack serves several quality levels: one ZIP, with a `QUALITY` choice, instead of one ZIP per level.

The ZIP must have `pack.json` and `shaders/` at its top level, not inside a folder. (Zipping a folder on macOS usually adds
that folder; zip its *contents*: `cd MyPack && zip -r ../MyPack.zip .`)

### A pack is complete and alone

Your pack supplies **every** shader the pipeline asks for. Nothing is ever taken from the built-in shaders or from another
pack, and shaders are never merged. If a required file is missing, or an `#include` names a file that is not in your ZIP,
the pack is refused: it shows in red in the menu with the reason and cannot be chosen.

The files that must exist are listed in `PackManager.REQUIRED` (24 files):

| File | What it draws |
|---|---|
| `override/sodium/blocks/block_layer_opaque.vsh/.fsh` | Terrain: solid, cut-out and translucent (water, glass). Replaces Sodium's own terrain shader. |
| `override/minecraft/core/block.vsh/.fsh` | Blocks drawn one at a time (falling sand, piston blocks, block entities) |
| `override/minecraft/core/entity.vsh/.fsh` | Mobs, players, armor, most other models |
| `override/minecraft/core/item.vsh/.fsh` | Items in the world, in frames and in hands |
| `override/minecraft/core/particle.vsh/.fsh` | Particles, rain and snow |
| `override/minecraft/core/rendertype_clouds.vsh/.fsh` | Clouds |
| `program/fullscreen.vsh` | The vertex shader of every full-screen pass: one triangle covering the screen |
| `program/sky.fsh` | The sky, drawn behind everything when the camera is under an open sky |
| `program/shadow_terrain.vsh/.fsh` | Terrain as the sun or moon sees it, into the shadow map |
| `program/effects.fsh` | Sun rays (color) and ambient occlusion (alpha), at half size |
| `program/effects_blur.fsh` | Softens that half-size image without smearing across depth edges |
| `program/effects_composite.fsh` | Lays the effects over the world image |
| `program/bloom_prefilter.fsh`, `bloom_downsample.fsh`, `bloom_upsample.fsh`, `bloom_composite.fsh` | The four steps of glow |
| `program/edges.fsh` | Last pass over the world image: smooths stair-stepped edges (Smooth Edges) |

A pass that the player has switched off in Shader Options (or that does not apply right now) is simply not run, but its file
must still be in the pack so that turning the option on never leaves a hole.

Files you do not need to touch can be copied unchanged from the built-in pack. You are free to reorganize `lib/` however you
like (folders, new files), as long as every `#include` resolves inside your ZIP.

## How the files are used

**`override/<namespace>/<path>`** replaces the shader that the game or another mod registered as `<namespace>:<path>`, only
while shaders are on. The pipeline stays theirs (vertex format, blending, uniforms); only the text changes. A pack can only
replace the shaders listed above. A shader of some other mod that has no file here is left as it is.

**`program/<name>`** is a shader of this mod's own passes, asked for as `metallum-extra:<name>`.

**Includes.** A line `#include "name.glsl"` is replaced by the file `lib/name.glsl` of your pack. Each library file goes in
once per shader, however many files ask for it, so library files may include what they need. Includes may be nested. The
include is textual: there is no include guard to write.

**Every shader file must start with its `#version` line** (the built-in ones use `#version 330`). The game inserts a
pipeline's `#define`s right after the first line. Comments are stripped before compiling. Shaders are compiled as GLSL, converted to
SPIR-V, then to Metal text by SPIRV-Cross.

## What your shaders can read

Each pipeline declares its own uniforms and samplers (the game's `Sampler0`, Sodium's `u_BlockTex`, and so on) and you can
only use ones that pipeline declares: see the built-in shader of the same name for the exact set. On top of that, **any** shader
of the pack may use these, which the mod fills in itself:

| Name | Kind | What |
|---|---|---|
| `MxGlobals` | uniform block (`std140`) | Per-frame values: the camera matrices, sun, moon and sky colors, shadow matrix, time, screen size, which effects are on. Declared for you by `#include "globals.glsl"`; read that file, every field is commented. |
| `MxShadowMap` | sampler2D | The shadow map's depth |
| `MxSceneColor` | sampler2D | A copy of the world image drawn so far (what water reflects and refracts) |
| `MxSceneDepth` | sampler2D | The matching depth |
| `MxLightColors` | sampler2D | A grid of block-light colors around the player (`light_colors.glsl`) |

`MxGlobals` includes, among others: `MxLightDir`/`MxLightColor` (the sun or moon, whichever lights the world),
`MxSkyZenith`/`MxSkyHorizon`/`MxSunsetColor`, `MxCameraPos` (`w` is seconds, for animation), `MxScreen`, `MxShadowMat`, and
`MxFeatures` and `MxParams2` which carry the player's toggles (sun rays, ambient occlusion, colored light, water
reflections, waving), so a pack can follow the options in Shader Options. `MxParams.x` is the **phase**: `mx_in_world()` is
true while the world is drawn; menus, the inventory and other screens use the game's colors, and the shaders must give the
game's own result there (see the built-in `override` shaders).

The textures the game and Sodium bind are limited by Metal: at most **16 textures per shader stage**.

Full-screen passes read the world through `InSampler` (the image being processed) and write `fragColor`. They run in this
order after the world is drawn: effects (sun rays and occlusion: `effects`, `effects_blur`, `effects_composite`), then glow
(`bloom_*`), then `edges`, then the held item is drawn.

The shadow map is drawn with `program/shadow_terrain.*` for terrain and with each `override` vertex shader for models:
a vertex shader must put its vertices where the light sees them while `MxParams.x` is the shadow phase. Draws whose shader
does not use `MxGlobals` are skipped while the map is drawn, so text and lines never scribble on it. Look at the built-in
`model.glsl` and `shadow_terrain.vsh`.

Colors: the world image holds display (sRGB-like) colors; lighting is added in linear light. `color.glsl` has the conversions.

## Starting point

The built-in pack is inside the mod's jar at `assets/metallum-extra/shaders/`:

```bash
mkdir MyPack && cd MyPack
unzip -q /path/to/metallum-extra-*.jar 'assets/metallum-extra/shaders/*' -d .
mv assets/metallum-extra/shaders shaders && rm -r assets
echo '{"format": 1}' > pack.json
zip -qr ../MyPack.zip .
```

or copy `src/main/resources/assets/metallum-extra/shaders/` from the source. Then change what you like.

### The smallest possible change

`program/edges.fsh` runs over the whole picture when Smooth Edges is on, which makes it a good first test. This version turns
red areas blue:

```glsl
#version 330

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    if (color.r > 0.5 && color.r > color.g * 1.5 && color.r > color.b * 1.5) {
        color.rgb = vec3(0.0, 0.0, color.r);
    }
    fragColor = color;
}
```

(The project's test packs do exactly this: `gradlew writeTestPacks -Pto=<folder>` writes `RedToBlue.zip` and `BlueToRed.zip`,
plus `SyntaxError.zip` and `Incomplete.zip` for seeing how mistakes are handled.)

## Working on a pack

- **Reload with R** (Reload Current Shaders, in Controls under Miscellaneous; it does nothing while shaders are off). It reads the folder and the pack's ZIP again
  and recompiles. Alternatively press **Apply** in the menu with the pack still highlighted.
- A pack is read into memory completely when it is chosen, so replacing the ZIP on disk never disturbs a running pack; the
  change shows after R or Apply.
- **Mistakes** show up when a shader is first compiled, which can be a moment after switching, in the middle of play. If
  compiling fails, the pack is put aside, the one that was working before (or the built-in shaders) takes over at once, and
  the game keeps running. The log has `Shader pack X is broken and is switched off:` followed by the shader's name and the
  compiler's message with a line number, and the menu shows the same in red under the list. Fix the file, zip again, choose
  the pack again.
- A pack that is merely incomplete never gets that far: it is refused when the menu reads the folder, with the missing file named.
- Packs can be quick to try because the translation to Metal text is cached on disk (`cache/metallum-extra/shaders/`): only
  shaders whose text actually changed are translated again. The log prints `MSL cache hits: N, MSL translations: M`.

### Development options

Start the game with these JVM arguments:

| Argument | Effect |
|---|---|
| `-Dmetallumextra.shaderDir=<folder>` | Read the **built-in** pack from a folder instead of the jar, so it can be edited without zipping (press R to reload) |
| `-Dmetallumextra.dumpShaders=<folder>` | Write the text of every shader the game compiles (as it was before replacing) to a folder; useful for seeing what a pipeline declares |
| `-Dmetallumextra.debugScript=<file>` | Run a script of steps (switch pack, take a screenshot, ...). The steps are listed at the top of `DebugScript.java` |
| `-Dmetallumextra.noMslCache=true` | Do not use the translation cache |

## Limits of format 1

- Packs use this mod's pipeline as it is: you can change what every pass computes, but not add passes, add uniforms,
  change a pipeline's vertex format, or change render targets.
- A pack's own options are choices and toggles only (no sliders), and they are compile-time defines, so changing one
  recompiles the shaders. The mod's own Shader Options (quality, shadows, glow, ...) reach your shaders through
  `MxGlobals` and are shown for every pack.
- No inheritance or merging between packs, and no shader downloads.
- Only Metal on macOS, and only with Sodium. Not together with Shine (see details.md).

## Packs in the Iris layout (preview)

A ZIP with a `shaders/` folder and **no** `pack.json` is read as an Iris/OptiFine-style pack. This is the beginning of
Iris compatibility, not all of it: it runs the full-screen stages of a frame over named buffers, and the game's own shaders
still draw the world.

What runs, in the order Iris runs it, with the programs found as `shaders/world0/<name>.vsh|fsh` (or `world-1/`, `world1/`,
or `shaders/<name>.vsh|fsh` for every dimension without its own):

| When | Programs |
|---|---|
| Before the world is drawn | `begin`, `begin1` ... `begin99`, then `prepare`, `prepare1` ... |
| Between opaque and translucent terrain | `deferred`, `deferred1` ... (the opaque world is `colortex0`; what they write goes back into the world) |
| After the world | `composite`, `composite1` ... `composite99`, then `final` |

Numbers count as numbers (`composite10` runs after `composite2`). A program needs both its `.vsh` and its `.fsh`; one
missing from the pack is simply not run. If there is no `final`, the screen shows `colortex0`.

**Buffers.** `colortex0` to `colortex15` (and the older names `gcolor`, `gdepth`, `gnormal`, `composite`, `gaux1` to `gaux4`)
and `depthtex0` to `depthtex2` can be sampled; only the ones the programs name are allocated.
- A program's fragment outputs go to the buffers in its `/* RENDERTARGETS: 0,3 */` (or `/* DRAWBUFFERS:03 */`) comment, in
  order: output 0 to the first, output 1 to the second. Without one, outputs go to buffers 0, 1, 2 ... as far as the
  program declares outputs. At most 8 at once.
- Every buffer is two textures. A program reads the main one and draws into the other, and then they swap, for every buffer it
  wrote. `flip.<program>.<buffer>=false` in `shaders.properties` (for example `flip.composite.colortex1=false`) switches that
  off for one buffer: the program then draws straight into the texture other programs read, and so must not sample that buffer.
- Buffers are cleared at the start of every frame: `colortex0` to the fog color, `colortex1` to white, the others to
  transparent black. In a program: `const bool colortex3Clear = false;` keeps a buffer between frames,
  `const vec4 colortex3ClearColor = vec4(...);` sets the color, and `const int colortex3Format = RGBA16F;` the format (`R8`,
  `RG8`, `RGBA8`, `R16`, `RG16`, `RGBA16`, `R16F`, `RG16F`, `RGBA16F`, `R32F`, `RG32F`, `RGBA32F`; three-channel and packed
  formats are stored with four channels).
- `depthtex1` is the depth before translucent terrain, `depthtex0` the depth after it. Translucent terrain only changes the
  depth where the game's own translucent shader wrote it (the opaque pixels of a glass texture, not its see-through middle), until
  the `gbuffers_water` program is supported. `depthtex2` (no hand) is the same as `depthtex1`: the hand is drawn after everything.
- `program.<name>.enabled=false` in `shaders.properties` switches a program off.

**Not run yet** (they are found and listed in the log): `setup`, `shadow`, `shadowcomp`, every `gbuffers_*` program,
compute shaders (`.csh`), geometry and tessellation shaders. A program that reads `shadowtex*`, `noisetex`, `shadowcolor*`
or a buffer above `colortex15` is refused with the name. Programs must be written in modern GLSL (`#version 330`, `in`/`out`,
`layout(location = n) out vec4 name;`, `gl_VertexID` for the vertex); the old `varying`, `gl_FragData` and `ftransform()` forms
need the standard uniforms and vertex inputs that come next. While such a pack is in use, the built-in effects (shadows,
sky, bloom, edges, ambient occlusion) are off.

The test packs in `TestPacks.writeIrisPacks` are small examples of each rule above.

### Standard uniforms in Iris-layout packs

A program declares the Iris uniforms it needs the usual way (`uniform mat4 gbufferModelView;`) and reads them by name. Metal has no
loose uniforms, so the mod takes those declarations out and puts the same names in one `std140` block, `IrisUniforms`, per program;
the program's code does not change. Provided now:

- **Matrices:** `gbufferModelView`, `gbufferModelViewInverse`, `gbufferProjection`, `gbufferProjectionInverse`,
  `gbufferPreviousModelView`, `gbufferPreviousProjection`.
- **Positions (vec3):** `cameraPosition`, `previousCameraPosition`, `sunPosition`, `moonPosition`, `shadowLightPosition`, `upPosition`
  (the last four in view space, 100 long), `fogColor`, `skyColor`.
- **Floats:** `eyeAltitude`, `sunAngle`, `shadowAngle`, `rainStrength`, `wetness`, `thunderStrength`, `frameTime`, `frameTimeCounter`,
  `viewWidth`, `viewHeight`, `aspectRatio`, `screenBrightness`, `near`, `far`, `nightVision`, `blindness`, `darknessFactor`.
- **Integers:** `frameCounter`, `worldTime`, `worldDay`, `moonPhase`, `isEyeInWater`, and `eyeBrightness`, `eyeBrightnessSmooth` (ivec2).

Conventions are Iris's, not the game's own:
- `gbufferProjection` is an ordinary OpenGL perspective projection with `near` 0.05 and `far` the render distance in blocks (chunks times 16),
  and the depth textures hold OpenGL depth to match: 0 at the near plane, 1 at the far plane. So
  `gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0)` divided by its `w` is the view-space position.
  (The game itself draws with depth running the other way; a full-screen pass converts it when `depthtex0` or `depthtex1` is read.)
- Buffers have OpenGL orientation: row 0 and `texcoord.y = 0` are the bottom of the screen.
- `sunAngle` is 0 at sunrise, 0.25 at noon, 0.5 at sunset and 0.75 at midnight. `shadowAngle` is `sunAngle` by day and `sunAngle - 0.5` by night.
  `sunPathRotation` (degrees) is read from `const float sunPathRotation = ...;` in the programs, and tilts the sun's and moon's path.
- `wetness` follows `rainStrength` with a half-life of 600 ticks, `eyeBrightnessSmooth` follows `eyeBrightness` with one of 10 ticks.
- `blindness` is 1 while the effect is on and 0 otherwise (the game fades it, and this does not yet).
- `frameTimeCounter` is real seconds, so it also runs while the game is paused. `cameraPosition` is a float of the true position;
  far from the origin it loses precision, as in Iris without `cameraPositionFract`.

An Iris uniform that is not provided yet is refused when the pack loads, with its name: the shadow matrices (with the shadow stage),
the per-object ones (`entityId`, `entityColor`, ...; with the `gbuffers_*` programs), `centerDepthSmooth`, `fogStart`, `fogEnd`, the
`...Fract`/`...Int` camera forms, held items, biomes. Custom uniforms from `shaders.properties` are not supported yet.
`const int colortexNFormat = RGBA16F;` lines are removed before compiling, since the format names are not GLSL.

### Terrain drawn by a pack's `gbuffers_*` programs

Sodium draws the terrain, and it draws it with the pack's program when the pack has one. For each layer Sodium draws, the program is the
first that exists in Iris's order:

| Layer | Programs tried, in order |
|---|---|
| Solid terrain | `gbuffers_terrain_solid`, `gbuffers_terrain`, `gbuffers_textured_lit`, `gbuffers_textured`, `gbuffers_basic` |
| Cut-out terrain (leaves, plants) | `gbuffers_terrain_cutout`, `gbuffers_terrain`, then the same three |
| Translucent terrain (water, glass, ice) | `gbuffers_water`, `gbuffers_terrain`, then the same three |

A layer with none of them is drawn with the game's own shader. The other `gbuffers_*` programs (entities, items, the hand, particles, sky,
weather ...) are found and listed in the log but not run yet.

Vertex inputs (the `core` profile names, `#version 330 core`): declare `in vec3 vaPosition;` and the others as usual.
- `vaPosition` (vec3): the vertex in the chunk's region; add `chunkOffset` (uniform, vec3) for the position relative to the camera.
- `vaColor` (vec4): the vertex color, alpha 1. `vaUV0` (vec2): the atlas coordinate. `vaUV2` (ivec2): the light map coordinate, 0 to 240.
- `vaNormal` (vec3): the direction the quad faces, one of the six axes; a quad that faces none of them closely (plants) reads as up.
  Sodium's mesh has no normals, so the mod writes the direction into spare bits when it builds the mesh.
- `mc_Entity` (vec2): `x` is -1 (block ids from `block.properties` are not supported yet), `y` is 1 for fluids and -1 for everything else.

Uniforms and samplers: `modelViewMatrix`, `modelViewMatrixInverse`, `projectionMatrix`, `projectionMatrixInverse`, `normalMatrix` (mat3),
`chunkOffset`, `textureMatrix` (identity), `alphaTestRef` (0 for solid terrain, 0.5 for cut-out, 0.01 for translucent), `atlasSize`,
`gtexture` (the block atlas), `lightmap`, and the standard uniforms above. `modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0)` is in view
space; `projectionMatrix` is the one the game's own depth buffer is drawn with, so the program's depth agrees with everything else the game draws.
A program that draws the world cannot sample `colortexN` or `depthtexN` (Iris gives it the atlas there; this version refuses it).

Outputs: `/* RENDERTARGETS: 0,1,2 */` as usual; the first output must go to `colortex0`, which is the game's own image. The other outputs go to the
buffers named, which later programs read: terrain is drawn into the buffers' main textures and nothing flips. The buffers are cleared at
the start of the frame, so only what the pack's programs draw into them is there; entities, the sky and the hand are drawn with the game's
own shaders for now and write `colortex0` only.

Not provided yet, and refused by name when the pack loads: `mc_midTexCoord`, `at_tangent`, `at_midBlock`, `vaUV1`, `mc_chunkFade`, the
`normals` and `specular` samplers, geometry shaders, and the compatibility profile (`gl_Vertex`, `gl_Color`, `ftransform()`, `varying`,
`gl_FragData`, `texture2D` ...; the next step translates it, and until then the error names the first such word it finds).
Sodium's fade-in of new chunks is not applied, and the atlas is read with Sodium's own coordinate nudge.
