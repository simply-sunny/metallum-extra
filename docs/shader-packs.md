# Making shader packs

A shader pack is a ZIP in the `shaderpacks/` folder of the game instance, laid out the way Iris and OptiFine lay theirs out:

```
Example.zip
└── shaders/
    ├── shaders.properties
    ├── lang/en_us.lang          (names for the options)
    ├── lib/...                  (anything the programs #include)
    ├── gbuffers_terrain.vsh|fsh, gbuffers_water ..., shadow.vsh|fsh, composite.vsh|fsh ... final.vsh|fsh
    └── world0/ world-1/ world1/ (programs for one dimension; the same names, used instead of the shared ones)
```

The mod's own shaders are such a pack, **Metallically Beautiful**, inside the jar (`assets/metallum-extra/pack/shaders/`): the best example of
everything below, and the file a new pack is most easily made from (copy that folder into a ZIP's `shaders/`). To edit it live, start the game
with `-Dmetallumextra.shaderDir=<that folder>`.

This is a start at Iris compatibility, not all of it. A pack written for this mod runs; an arbitrary Iris pack may not, yet: programs must be in
modern GLSL (`#version 330`, `in`/`out`), geometry, compute and tessellation shaders, `block.properties` and custom uniforms are not supported, and
the packs of other mods' formats are not read. A pack that reads this mod's own extensions (below) runs only here.
This mod's earlier pack format (`pack.json`, `program/`, `override/`) is no longer read; the ZIP is listed with that reason.

## What runs, and when

Programs are found as `shaders/world0/<name>.vsh|fsh` (or `world-1/`, `world1/`, or `shaders/<name>.vsh|fsh` for every dimension without its own).
A program needs both its `.vsh` and its `.fsh`.

| When | Programs |
|---|---|
| Start of the frame | `begin`, `begin1` ... |
| The shadow map | `shadow` (and `shadow_solid`, `shadow_cutout`, `shadow_entities`, `shadow_block`) |
| Before the world | `prepare`, `prepare1` ... |
| While the world is drawn | the `gbuffers_*` programs, below |
| Between opaque and translucent terrain | `deferred`, `deferred1` ... (the opaque world is `colortex0`; what they write goes back into the world) |
| After the world | `composite`, `composite1` ... `composite99`, then `final` |
| Then, drawn over the finished image | the held item and arm, with the game's own shaders unless the pack has `gbuffers_hand` |

Numbers count as numbers (`composite10` runs after `composite2`). If there is no `final`, the screen shows `colortex0`.

**Buffers.** `colortex0` to `colortex15` (and the older names `gcolor`, `gdepth`, `gnormal`, `composite`, `gaux1` to `gaux4`)
and `depthtex0` to `depthtex2` can be sampled; only the ones the programs name are allocated.
- A program's fragment outputs go to the buffers in its `/* RENDERTARGETS: 0,3 */` (or `/* DRAWBUFFERS:03 */`) comment, in
  order. Without one, outputs go to buffers 0, 1, 2 ... as far as the program declares outputs. At most 8 at once.
- Every buffer is two textures. A program reads the main one and draws into the other, and then they swap, for every buffer it
  wrote. `flip.<program>.<buffer>=false` in `shaders.properties` switches that off for one buffer: the program then draws straight into the
  texture other programs read, and so must not sample that buffer.
- Buffers are cleared at the start of every frame: `colortex0` to the fog color, `colortex1` to white, the others to
  transparent black. In a program: `const bool colortex3Clear = false;` keeps a buffer between frames,
  `const vec4 colortex3ClearColor = vec4(...);` sets the color, and `const int colortex3Format = RGBA16F;` the format (`R8`,
  `RG8`, `RGBA8`, `R16`, `RG16`, `RGBA16`, `R16F`, `RG16F`, `RGBA16F`, `R32F`, `RG32F`, `RGBA32F`; three-channel and packed
  formats are stored with four channels).
- **Sizes.** `size.buffer.colortex4=0.5 0.5` in `shaders.properties` gives a buffer half the screen's width and height (numbers below 16 are shares
  of the screen, larger ones pixels). A program draws into the size of the buffers it writes, so they must all be the same size; `colortex0` is the
  world's image and always full size. The built-in pack's bloom is a chain of such buffers.
- `depthtex1` is the depth before translucent terrain, `depthtex0` the depth after it, as OpenGL depth. `depthtex2` (no hand) is the same as
  `depthtex1`: the hand is drawn after everything.
- `program.<name>.enabled=<condition>` in `shaders.properties` switches a program off. The condition is `true`, `false`, or a test of the pack's
  options: names, numbers, `!`, `&&`, `||`, `==`, `!=`, `<`, `<=`, `>`, `>=` and parentheses (`BLOOM && QUALITY >= 2`).

**Options.** Found in the pack's shader files, as in Iris, and shown under Shader Options (one screen per pack):
- `#define QUALITY 2 // [1 2 3]` is a choice among the listed values (the value in the line is the default);
  `const int shadowMapResolution = 2048; // [1024 2048 4096]` the same for a constant;
- `#define BLOOM` and `//#define BLOOM` are an on-or-off switch, on and off by default.
The player's choices are written into the files as they are read (a chosen value replaces the one in its line, a switch is commented out or
in), so programs, buffers and the conditions above all see them; changing one compiles the pack again. Names for the screen come from
`lang/en_us.lang` (`option.QUALITY=Quality`). What a program asks for is what its preprocessor would leave: a sampler behind
an `#ifdef` of an option that is off is not asked for.

## Shadows

A `shadow` program draws the shadow map: everything the sun (or moon) sees within `const float shadowDistance` blocks (default 160) of the
player, into a `const int shadowMapResolution` (default 1024) square map. Terrain, mobs, items and moving blocks are drawn with it; text, lines
and the round blob under a mob are not. Its vertex stage reads the same inputs as terrain programs do (`vaPosition`, `chunkOffset`, `mc_Entity` ...),
and `modelViewMatrix` and `projectionMatrix` in it are the light's: `shadowModelView` (the light's view of the world around the camera) and
`shadowProjection` (orthographic, reaching the shadow distance plus 8 blocks). What it writes goes to `shadowcolor0`; its depth is `shadowtex0`
and `shadowtex1`, OpenGL depth (0 nearest the light, 1 where nothing was drawn). Any program may sample them. A shadow program written the usual way
(`gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0)`) needs no other change.

Terrain is drawn into the map only when it has gone stale: the light has turned more than 0.2 degrees, the player is more than 8 blocks from the
map's center, a chunk mesh changed, or the pack, its options or the map's size changed; in between, a copy of it is reused and only the mobs are
drawn again. Between refreshes `shadowModelView` includes how far the player has walked since, so programs read it as they would anywhere.
Without a `shadow` program there is no shadow map and `shadowtex0` reads as empty.

## The sky, clouds and the rest of the world

| What | Programs tried, in order |
|---|---|
| Solid terrain | `gbuffers_terrain_solid`, `gbuffers_terrain`, `gbuffers_textured_lit`, `gbuffers_textured`, `gbuffers_basic` |
| Cut-out terrain (leaves, plants) | `gbuffers_terrain_cutout`, `gbuffers_terrain`, then the same three |
| Translucent terrain (water, glass, ice) | `gbuffers_water`, `gbuffers_terrain`, then the same three |
| Mobs, players, armor, items on the ground and in frames, block entities | `gbuffers_entities`, `gbuffers_textured_lit`, `gbuffers_textured`, `gbuffers_basic` |
| The same, translucent | `gbuffers_entities_translucent`, then `gbuffers_entities` and the rest |
| The glowing parts of mobs (eyes, a charged creeper) | `gbuffers_spidereyes`, `gbuffers_textured`, `gbuffers_basic` |
| Blocks that are not in a chunk (falling blocks, pistons moving) | `gbuffers_block`, then `gbuffers_terrain` and the rest |
| The same, translucent | `gbuffers_block_translucent`, then `gbuffers_block` ... |
| Particles / translucent particles | `gbuffers_particles` / `gbuffers_particles_translucent`, then `gbuffers_textured_lit`, `gbuffers_textured`, `gbuffers_basic` |
| Rain and snow | `gbuffers_weather`, `gbuffers_textured_lit`, `gbuffers_textured`, `gbuffers_basic` |
| Clouds | `gbuffers_clouds`, `gbuffers_textured`, `gbuffers_basic` |
| The held item and arm (drawn after `final`) | `gbuffers_hand` (translucent parts `gbuffers_hand_water`), then `gbuffers_textured_lit`, `gbuffers_textured`, `gbuffers_basic` |
| The sky | `gbuffers_skybasic` |

A pipeline with none of its programs is drawn with the game's own shader. The menus, the inventory and the HUD keep the game's shaders.

**The sky** is drawn over the whole screen, by one program, in place of the game's sky disc, its sunrise fan and the dark disc below the
horizon (which the pack with a `gbuffers_skybasic` no longer gets). Its vertex stage reads `vaPosition`, three vertices of a triangle that covers the
screen, as points on the far plane in the camera's world; the usual `projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0)` therefore works,
and the direction a pixel looks in is `gbufferProjectionInverse` and `gbufferModelViewInverse` of its coordinates. The sun, moon and stars are still
the game's and are drawn on top, on the path `sunPathRotation` tilts. The game's own sky is used while the camera is under water or in a dimension
without a sun.

**Water and other translucent things.** The programs for translucent terrain, entities, blocks and particles (`gbuffers_water`, `*_translucent`)
may read `colortex0` and `depthtex0`, `depthtex1`, `gdepthtex`: the world as it was just before translucent things were drawn (all three depth
textures are that depth). That is how the built-in pack's water refracts and mirrors the land.

**The hand** is drawn after the composite and final programs, over the finished image, so the programs after the world cannot lighten or blur it.
(Iris draws it before them.) A pack with no `gbuffers_hand` and none of its fallbacks leaves it to the game's shaders, as the built-in pack does.

## Standard uniforms

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

- **Shadow matrices:** `shadowModelView`, `shadowModelViewInverse`, `shadowProjection`, `shadowProjectionInverse` (see Shadows).

An Iris uniform that is not provided yet is refused when the pack loads, with its name: the per-object ones (`entityId`, `entityColor`, ...),
`centerDepthSmooth`, `fogStart`, `fogEnd`, the `...Fract`/`...Int` camera forms, held items, biomes. Custom uniforms from `shaders.properties`
are not supported yet.
`const int colortexNFormat = RGBA16F;` lines are removed before compiling, since the format names are not GLSL.


## Vertex inputs and what the programs read

### Terrain

Sodium draws the terrain, and it draws it with the pack's program when the pack has one.

A layer with none of them is drawn with the game's own shader.

Vertex inputs (the `core` profile names, `#version 330 core`): declare `in vec3 vaPosition;` and the others as usual.
- `vaPosition` (vec3): the vertex in the chunk's region; add `chunkOffset` (uniform, vec3) for the position relative to the camera.
- `vaColor` (vec4): the vertex color, alpha 1. `vaUV0` (vec2): the atlas coordinate. `vaUV2` (ivec2): the light map coordinate, 0 to 240.
- `vaNormal` (vec3): the direction the quad faces, one of the six axes; a quad that faces none of them closely (plants) reads as up.
  Sodium's mesh has no normals, so the mod writes the direction into spare bits when it builds the mesh.
- `mc_Entity` (vec2): `x` is -1 (block ids from `block.properties` are not supported; a pack can ask for this mod's own kinds of block, see
  Extensions), `y` is 1 for fluids and -1 for everything else. `mc_midTexCoord` (vec2) is only good for telling which side of a quad's texture a
  vertex is on (Sodium's mesh has no texture centers): compare it with `vaUV0`. `mc_chunkFade` (float) is 0 for a section just built and 1 once it
  has faded in from the fog, as Sodium fades it.

Uniforms and samplers: `modelViewMatrix`, `modelViewMatrixInverse`, `projectionMatrix`, `projectionMatrixInverse`, `normalMatrix` (mat3),
`chunkOffset`, `textureMatrix` (identity), `alphaTestRef` (0 for solid terrain, 0.5 for cut-out, 0.01 for translucent), `atlasSize`,
`gtexture` (the block atlas), `lightmap`, and the standard uniforms above. `modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0)` is in view
space; `projectionMatrix` is the one the game's own depth buffer is drawn with, so the program's depth agrees with everything else the game draws.
A program that draws the world samples `gtexture`, `lightmap`, `shadowtex0`, `shadowtex1` and `shadowcolor0`; only the translucent ones also `colortex0` and the depth textures (above).

Outputs: `/* RENDERTARGETS: 0,1,2 */` as usual; the first output must go to `colortex0`, which is the game's own image. The other outputs go to the
buffers named, which later programs read: terrain is drawn into the buffers' main textures and nothing flips. The buffers are cleared at
the start of the frame, so only what the pack's programs draw into them is there; entities, the sky and the hand are drawn with the game's
own shaders for now and write `colortex0` only (the programs below draw entities and the others into the buffers too).

Not provided yet, and refused by name when the pack loads: `at_tangent`, `at_midBlock`, the `normals` and `specular` samplers, `noisetex`, geometry
shaders, and the compatibility profile (`gl_Vertex`, `gl_Color`, `ftransform()`, `varying`, `gl_FragData`, `texture2D` ...; the error names the
first such word it finds). The atlas is read with Sodium's own coordinate nudge.

### Entities, items, particles, weather, clouds

The game's own pipelines for these are drawn with the pack's program instead, found by what the pipeline draws (the table above).

These programs read the vertex inputs of the pipeline they replace: `vaPosition`, `vaColor` (already multiplied by the game's color
modulator), `vaUV0`, `vaUV1` (ivec2, the overlay: hurt flash and creeper charge), `vaUV2` (the light), `vaNormal`; whatever a pipeline has no
element for reads a neutral value (white, up, full light). `mc_Entity` is -1 and `mc_chunkFade` 1 for all of them, as in Iris. Clouds have no vertex buffer in the game; a cloud program reads `vaPosition` (the corner, camera-relative), `vaColor` (the face's shade times the cloud color) and `vaNormal`, made from the game's table of faces. The matrices (`modelViewMatrix`,
`projectionMatrix`, ...) are the game's own, per draw; `chunkOffset` is 0. A program that cannot be given something its pipeline lacks (it
reads `gtexture` but the pipeline has no texture) does not draw that pipeline: the game's shader does, and the log says so.

Everything the world is drawn with writes the same buffers: buffer *n* of a program's `RENDERTARGETS` is attachment *n* of the pass, so all
the programs drawing the world must use buffers below 8 and the pack's buffers 1 and up are cleared once per frame like terrain's. A
program that does not write a buffer leaves it as it was.

The glowing parts of mobs, and entity pipelines with the game's other defines (`EMISSIVE`, `NO_OVERLAY`, `NO_CARDINAL_LIGHTING`, `DISSOLVE`,
`APPLY_TEXTURE_MATRIX`, `ALPHA_CUTOUT`), compile with those defines set, so a program can `#ifdef` on them.

## Extensions (not Iris)

These exist so the built-in pack could be moved onto this pipeline without being redesigned. A pack that uses one runs only in this mod.
- **Lighting model uniforms**, all `vec4` unless noted, worked out each frame (see `ShaderGlobals` and `lib/uniforms.glsl` of the built-in pack):
  `MxLightDir`, `MxLightColor`, `MxSunDir`, `MxMoonDir`, `MxSkyAmbient`, `MxBlockLight`, `MxMinAmbient`, `MxSkyZenith`, `MxSkyHorizon`,
  `MxSunsetColor`, `MxLightGrid`, `MxFog`, `MxFogEnds`, `MxFogColor`, `MxFlags`, and `float MxEyeSky`.
- **`MxLightColors`** (sampler2D): the color of block light around the player, kept up to date only while a program declares it.
- **`mx.blockTypes=true`** in `shaders.properties`: `mc_Entity.x` in terrain programs is this mod's own kind of block (1 water, 2 lava, 3 leaves, 4 plant,
  5 upper half of a plant, 6 shiny, 7 metal, 32 and up a light source: 32 + 16 * its light color + its level) instead of -1.
- **`mx_overlay_color(ivec2)`**, in the vertex stage of entity programs: the overlay texture at `vaUV1` (alpha 1 means no overlay; 1.0 where the pipeline has none).

## Deviations from Iris

- The hand is drawn after `final` (above). Block entities are drawn with `gbuffers_entities`, and `gbuffers_block` is only for blocks that are not
  in a chunk.
- Buffers 1 and up are attached to every pass that draws the world, so all programs drawing the world together may write buffers 0 to 7 only.
- `entityColor`, `entityId`, `blockEntityId`, `block.properties`, custom uniforms, `fogStart`/`fogEnd`, compute shaders, and the compatibility profile
  are not provided; geometry shaders are refused.
- The shadow map reaches 8 blocks further than `shadowDistance`, to let the terrain in it be reused; `shadowDistance` is where a pack should fade.

## The test packs

The packs in `src/test/java/com/metallumextra/TestPacks.java` are small examples of each rule above, and `src/test/iris-pipeline/run.sh`,
`src/test/pack-regression/run.sh` and `src/test/baseline/run.sh` run them in the game.
