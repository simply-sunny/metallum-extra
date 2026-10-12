# OpenGL to Metal: what is still missing

State on 2026-10-11 (Metallum Extra 0.2.0 on Metallum 0.0.25, Minecraft 26.3). This is the working sheet for what remains. The
[translation table](translation-table.md) lists what was already done and is partly out of date. The per-pack results behind the
"Blocks" columns are in [shader-compatibility](shader-compatibility.md).

Path of every shader: pack GLSL (legacy or modern) -> `#version 330 core` (`LegacyGlsl`, `IrisTerrain`, `IrisWorldAdapter`) -> glslang
-> SPIR-V -> SPIRV-Cross -> Metal Shading Language. Compute: GLSL 430 -> SPIR-V -> MSL kernel, dispatched between render passes.

Priority: **P1** blocks packs that otherwise work or crashes the game, **P2** blocks one or two packs, **P3** polish or rarely used.

Status: **Done** = in the tree and compiles (`compileJava`, `compileTestJava`); **Untested** = not run in the game or `packTest` yet
(testing was deferred on purpose); **Open** = still to do; **Blocked** = needs something outside this mod (named in the row).

Score (rows of sections 1 to 4; timeout and `shadow_water` are left out): **33 of 40 done** in code, 23 of those untested in the game; 4 partly done, 1 open (`blockEntityId`), 1 blocked (storage images in render programs), 1 refused by design. Section 5 (six packs with visible bugs) is not counted.

Rule for this pass: where OpenGL defines the arithmetic, Metal must do the same arithmetic. Fast-math is off, comparisons and
depth conversions follow OpenGL's definitions, and the emulated things say where they differ.

## 1. GLSL constructs and names

| Item | Status | Blocks | Prio | Note |
|---|---|---|---|---|
| `shadow` sampler (a shadow map read without a `shadowtex*` name) in `composite`/`deferred` | Done in code (untested) | Bliss, RedHat | P1 | `IrisPlan` maps `shadow`/`watershadow` to the shadow depth in full-screen programs. |
| `gl_Fog` as a whole (`gl_Fog.color`, `.density`) | Done (untested) | TAA Project | P2 | `LegacyGlsl` maps `.color` to `fogColor`, `.density` to `fogDensity`. |
| `gl_FrontColor`, `gl_BackColor`, `gl_TexCoord[n]`, `gl_FrontMaterial`, `gl_LightSource` | Refused with the name, by design | not seen in the 26 packs | P3 | Fixed-function state has no Metal counterpart; a pack that reads it is refused with the name. |
| Unary minus on a vec3 in a custom `variable.*` | Done (untested) | Bloop | P2 | `CustomUniforms` parses `-name` per component. |
| Custom-uniform `random()` | Done (untested) | none now | P3 | OptiFine's list is complete now: `random` is the last of it. |
| `uniform sampler2D tex` read in `prepare` | Done (untested) | I Like Vanilla | P2 | `tex` is `colortex0` in every program that is not a world program. |
| Integer colour formats (`RGBA32UI`, `R32I` ...) for `colortexN` | Done (untested) | Noble | P2 | `IrisPlan.formatNamed`: the 8/16/32-bit `UI` and `I` formats, read nearest (`IrisPipeline#kindOf`), written as `uvec4`/`ivec4` (`IrisWorldAdapter#OUTPUT_NAMED`). |
| Geometry shaders (`.gsh`) | Partly: the stage is replaced | iterationT | P2 | The geometry stage is not run; `GeometryStage` does what it did in its place. Sky: the vertex outputs keep the names the fragment stage reads, and `isMoon` is the stage the game draws the sun or the moon with (exact: each is its own pipeline). Terrain: the names are passed on, and `textureResolution` is 16 texels (the vanilla sprite size, or the pack's `TEXTURE_RESOLUTION` when set); the geometry stage derived it per triangle, so it is an approximation for HD sprites. Per-triangle emulation (`GeometryKernel`, built for the sky, not wired) is not done. |
| `#version` below 140 in the sky / fullscreen path | Done (untested) | miniature-shader | P1 | Every `.vsh`/`.fsh` goes through `LegacyGlsl.translate` in `ShaderPack.loadUncached`, the sky included, which is the path `IrisWorld` reads. |
| A varying read by the fragment stage but never declared by the vertex stage | Done | OPAL, Vibrant | - | `GlslLocations.fit` (dummy output). |
| Duplicate `#define projectionMatrix` in shadow programs | Done (untested) | Noble | P1 | `IrisWorldAdapter#withPipelineNamesUndefined`: the prelude's define is undefined just before the pack's. |
| `#ifdef X // comment`, `0.75// [..]`, directives in block comments, multi-name uniform lists, `blockEntityId` attribute, `CAT_*` constants, `gl_FogFragCoord`, `fmod` | Done | see compatibility doc | - | |
| Names that Metal's library also defines (`saturate`, `length_squared`, ...) | Done (renamed) | Photon | - | List `METAL_FUNCTIONS`; extend when a pack names another. |
| C++ keywords used as variable names (`new`, `sampler`, ...) | Done (prefixed) | several | - | |

## 2. Resources and binding

| Item | Status | Blocks | Prio | Note |
|---|---|---|---|---|
| More than 16 samplers in a stage | Done | Photon | - | Metal limit is 16 per stage; sharing is by MSL entry point only. |
| A sampler declared but not bound by the pipeline (`colortex6`, `shadowtex0`) | Done (untested) | Mellow, Velocity, E-LITE | P1 | `IrisWorldAdapter#boundSamplers`: a sampler with no slot is dropped and a program that reads it gets a white texel. |
| 3D textures from `.dat` (`TEXTURE_3D`) | Done | Photon | - | |
| `image2D`, `image3D`, `imageLoad/Store` on `image.*` custom images, in compute | Done (untested) | Photon coloured lights, Mellow | P2 | `ProgramSet#customImages`, `IrisTargets#createImage`; the same picture is written by compute and read by the program's sampler name (`mx:image:<name>`). |
| `image.*` three-dimensional images (`voxel_img`, `light_img`) | Done (untested) | Photon, Mellow, BSL, Complementary | P2 | `IrisTargets#createVolume`: a Metal 3D texture made by `MetallumExtraNative`, with the game given a one-pixel stand-in as for the pack's own 3D textures. Not cleared each frame (a warning says so). |
| `imageStore` / `imageLoad` from a render program (vertex or fragment) | **Blocked** | Photon coloured lights (voxelisation), BSL, Complementary | P2 | renderpearl's pipeline bindings have only `COMBINED_IMAGE_SAMPLER`, `UNIFORM_BUFFER` and `TEXEL_BUFFER`, so no storage image or buffer can be bound to a render pipeline. Needs a renderpearl change. |
| `layout(std430) buffer` (SSBOs) in compute, `bufferObject.N = bytes` | Done (untested) | none seen in the 26 packs | P3 | `IrisPipeline#storageBuffer`; bound by the GLSL `binding`. SSBOs in render programs: **Blocked** (same as above). |
| `atomicAdd/Or/Max/Min` on images or buffers in compute | Done (untested) | Photon floodfill | P2 | SPIRV-Cross emits the MSL atomics; the resources are the image and buffer rows above. In render programs: **Blocked**. |
| `shadowcolor1` (second shadow colour attachment) | Done (untested) | Complementary Reimagined LOW+ | P2 | `IrisTargets` makes it when a program uses it. |
| `depthtex2` without the hand | Done (untested) | Complementary, BSL (minor) | P3 | `depthtex2` is its own copy of the depth taken before the held item goes in (`IrisTargets#depthWithoutHand`, `IrisPipeline#afterWorld`); `depthtex0` keeps the hand. Also bound to world programs (`MxDepth2`). |
| `colortexN` formats RGB8 / RGB16F / packed | Widened to four channels | all | - | Metal has no three-channel or RG11B10 render format here. |
| Texture formats beyond the above (`R11F_G11F_B10F`) | Done (untested) | Photon | - | A colour buffer of that format is `RG11B10_FLOAT` (`IrisPlan#formatNamed`). |

## 3. Metal specifics

| Item | Status | Note | Prio |
|---|---|---|---|
| Fast-math and NaN | Done (untested) | `MetalUtilitiesMixin` compiles every MSL library with `fastMathEnabled = false` and `mathMode = Safe` (Metallum passed no options, which Metal reads as fast-math on). This costs some speed in the shaders of every pack, and the cost is not measured yet. | P2 |
| Threadgroup memory 32 KB | Worked around (`packed_float3`) | Photon sky SH hit 36 KB with `float3` (16 B). Any other compute program with large `shared` arrays will hit it. | P3 |
| `gl_FragCoord` origin, depth range 0..1 vs -1..1 | Done (pass flip, depth conversion) | | - |
| `sampler2DShadow` | Emulated, follows GL | Four texel compares blended bilinearly at GL's texel centres (`LegacyGlsl#SHADOW_COMPARE`), for `texture` and `textureLod` (the LOD is ignored). What is left is precision: GL's hardware weights are 8-bit fixed point, so results are not bit-exact. | P3 |
| Command-buffer timeout (5 s) | Not a translation problem | Appears when the GPU is saturated by other apps; Metallum raises "5s timeout reached when waiting for Metal submit completion". Retry. | P3 |

## 4. Render pipeline (not GLSL)

| Item | Status | Blocks | Prio |
|---|---|---|---|
| Failure of a pipeline Sodium or the sky builds crashes the frame instead of putting the pack aside | Done (untested) | Mellow, E-LITE, miniature | P1 |
| Held item drawn before the deferred stage (so Photon lights it) | Written, unreliable, off by default (`-Dmetallumextra.earlyHand=true`); **needs a game run** to find why no hand pass runs | Photon hand | P1 |
| `gbuffers_damagedblock` (`core/rendertype_crumbling`), `gbuffers_line` (`core/rendertype_lines`) | Done (untested) | cosmetic | P3 |
| `gbuffers_entities_glowing` (the game's outline pass, `core/rendertype_outline`) | Done (untested) | cosmetic | P3 |
| Glowing outline output goes to the entity outline target | Done (untested): the glowing program's first output is written to the target the game composites; no other program is used for the outline (`IrisPlan.Use#GLOWING`, `IrisWorld#make`) | cosmetic | P3 |
| Mob hurt and burning tint (`entityColor`, BSL's `mix(albedo, entityColor, a)`) | Done (untested): the vertex stage reads the overlay texel of `UV1` and passes it flat to the fragment stage (`IrisWorldAdapter#adapt`). | BSL and other packs that tint hurt mobs | P1 |
| `shadow_water` | No game draw maps to it in this version, so it is not run | - | P3 |
| `renderStage` uniform | Partly set: the sun and the moon get their stage; other stages stay 0 | BSL/Complementary small effects | P3 |
| `entityId` from `entity.properties` | Done (untested): an entity with an id is drawn with a pipeline that has the id as a constant, and its draws are not merged with other entities (`IrisWorld#entityPipelineFor`, `ModelFeatureRendererEntityMixin`). Not with improved transparency (OIT), where the default pipeline is kept. | packs that tint by entity | P3 |
| `blockEntityId` from `block.properties` | Open, zero | packs that tint by block entity | P3 |
| `mc_midTexCoord`, `at_tangent`, `at_midBlock`, PBR atlases | Terrain has them (worked out per quad); models get approximations; no PBR atlas data | PBR packs | P3 |

## 5. Visible bugs of packs that already load

These are not translation gaps but wrong output; each needs a capture of the buffers (the debug properties `metallumextra.debugBuffer` and `debugAfter` exist) to find the first wrong pass. **Open**: not looked at in this pass, because they need the game running.

| Pack | Symptom |
|---|---|
| Sildur's Vibrant | World black, HUD fine |
| MakeUp UltraFast | Almost black at midday |
| BakedPotato | White blotches over terrain |
| OPAL | Sky blown out to white |
| Sildur's Basic | Red-tinted sky |
| Photon | Darker and greener than the reference; hand not lit |

## Still open, in order

1. Run the game on the packs above (`src/test/pack-regression`, `src/test/iris-pipeline`, `packTest`): everything marked *Untested* needs it.
2. Held item before deferred (Photon's hand): find why no hand pass runs in the early mode (`readyForHand` is the first suspect).
3. Geometry stage of iterationT: the terrain's texture resolution is the vanilla sprite size, not measured per triangle.
4. Blocked on renderpearl: storage images and buffers in render pipelines (vertex `imageStore`, SSBOs and atomics in gbuffers/composite). This is the rest of Photon's coloured lights.
5. `blockEntityId`, and `renderStage` beyond the sun and the moon.
6. Section 5, one pack at a time, with buffer captures.
