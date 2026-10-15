# OpenGL to Metal: what is still missing

State on 2026-10-11 (Metallum Extra 0.2.0 on Metallum 0.0.25, Minecraft 26.3). This is the working sheet for what remains; the
[translation table](translation-table.md) lists what was already done and is partly out of date (it still says `colortex8`+ and compute are
unsupported). The per-pack results behind the "Blocks" columns are in [shader-compatibility](shader-compatibility.md).

Path of every shader: pack GLSL (legacy or modern) -> `#version 330 core` (`LegacyGlsl`, `IrisTerrain`, `IrisWorldAdapter`) -> glslang
-> SPIR-V -> SPIRV-Cross -> Metal Shading Language. Compute: GLSL 430 -> SPIR-V -> MSL kernel, dispatched between render passes.

Priority: **P1** blocks packs that otherwise work or crashes the game, **P2** blocks one or two packs, **P3** polish or rarely used.

## 1. GLSL constructs and names

| Item | State | Blocks | Prio | Note |
|---|---|---|---|---|
| `shadow` sampler (a shadow map read without a `shadowtex*` name) in `composite`/`deferred` | **Done** | Bliss, RedHat | - | Bound shadow depth map under `shadow`/`watershadow` for post programs. |
| `gl_Fog` as a whole (`gl_Fog.color`, `.density`) | **Done** | TAA Project | - | `gl_FogFragCoord`, `color`, `density`, `start`, `end`, and `scale` translated. |
| `gl_FrontColor`, `gl_BackColor`, `gl_TexCoord[n]`, `gl_FrontMaterial`, `gl_LightSource` | Refused with the name | not seen in the 26 packs | P3 | Fixed-function leftovers; refusal is fine. |
| Unary minus on a vec3 in a custom `variable.*` | **Done** | Bloop | - | Supported in `CustomUniforms`. |
| Custom-uniform vector constructors & expressions (`vec2/3/4(...)`, element-wise math) | **Done** | RenderPearl | - | Full vector expressions and swizzling in `CustomUniforms`. |
| Matrix component access (`matrix.col.row`, `matrix.col.xyzwrgba`) | **Done** | RenderPearl | - | Supported in `CustomUniforms`. |
| `textureLod` on `sampler2DShadow` | **Done** | iterationT | - | Overloaded `mx_shadowCompare` with lod support in `LegacyGlsl`. |
| `uniform sampler2D tex` read in `prepare` | **Done** (neutral texture fallback) | I Like Vanilla | - | Neutral texture bound for unmapped prepare samplers. |
| Integer colour formats (`RGBA32UI`, `R32I`) for `colortexN` | Refused | Noble | P2 | Needs `usampler2D` reads and integer render targets in the buffer allocator. |
| Geometry shaders (`.gsh`) | Refused | iterationT | P2 | Metal has no geometry stage; would need emulation by vertex expansion. |
| `#version` below 140 in the sky / fullscreen path | **Done** | miniature-shader | - | `adaptSky` translates legacy versions to `#version 330 core`. |
| A varying read by the fragment stage but never declared by the vertex stage | **Done** (dummy output) | OPAL, Vibrant | - | `GlslLocations.fit`. |
| Duplicate `#define projectionMatrix` in shadow programs | **Done** | Noble | - | Prelude undefines conflicting macros before pack code. |
| `#ifdef X // comment`, `0.75// [..]`, directives in block comments, multi-name uniform lists, `blockEntityId` attribute, `CAT_*` constants, `gl_FogFragCoord`, `fmod` | **Done** | see compatibility doc | - | |
| Names that Metal's library also defines (`saturate`, `length_squared`, ...) | Done (renamed) | Photon | - | List `METAL_FUNCTIONS`; extend when a pack names another. |
| C++ keywords used as variable names (`new`, `sampler`, ...) | Done (prefixed) | several | - | |

## 2. Resources and binding

| Item | State | Blocks | Prio | Note |
|---|---|---|---|---|
| More than 16 samplers in a stage | Done for the fragment entry point (sampler slots shared) | Photon | - | Metal limit is 16 per stage; sharing is by MSL entry point only. |
| A sampler declared but not bound by the pipeline (`colortex6`, `shadowtex0`) | Pipeline compile fails | Mellow, Velocity, E-LITE | P1 | Bind a dummy texture of the right type, or drop an unused declaration at load. This one change turns two crashes into working or cleanly refused packs. |
| 3D textures from `.dat` (`TEXTURE_3D`) | Done | Photon | - | |
| `image2D/3D`, `uimage3D`, `imageLoad/Store` from `image.*` properties | Compute-local images done; `image.*` custom images not | Photon coloured lights, Mellow | P2 | `IrisPlan.Image.buffer < 0` throws. Needs storage textures in the right pixel formats and the MTLEvent ordering already used for compute. |
| `imageStore` from a vertex shader (Photon voxelisation) | Not translated | Photon coloured lights | P2 | Needs vertex-stage writable resources in the pipeline description. |
| `layout(std430) buffer` (SSBOs), `buffer.*` properties | Not translated | none seen working | P3 | Throws today. |
| `atomicAdd/Or/Max/Min` on images or buffers | Not translated | Photon floodfill | P2 | SPIRV-Cross emits MSL atomics, the resource side is missing. |
| `shadowcolor1` (second shadow colour attachment) | Not provided | Complementary Reimagined LOW+ | P2 | Needs a second colour attachment on the shadow pass. |
| `depthtex2` without the hand | Contains the hand today | Complementary, BSL (minor) | P3 | Needs a separate depth copy. |
| `colortexN` formats RGB8 / RGB16F / packed | Widened to four channels | all | - | Metal has no three-channel or RG11B10 render format here. |
| Texture formats beyond the above (`R11F_G11F_B10F`) | Handled in trace decoding only | Photon | P3 | |

## 3. Metal specifics

| Item | State | Note | Prio |
|---|---|---|---|
| Fast-math and NaN | Not controlled | Metal compiles with fast-math on; packs that rely on NaN/Inf behaviour (Photon sky) may differ. Set the compile option off or add `isnan` guards. | P2 |
| Threadgroup memory 32 KB | Worked around (`packed_float3`) | Photon sky SH hit 36 KB with `float3` (16 B). Any other compute program with large `shared` arrays will hit it. | P3 |
| `gl_FragCoord` origin, depth range 0..1 vs -1..1 | Done (pass flip, depth conversion) | | - |
| `sampler2DShadow` | Emulated (2x2 compare) | Not bit-exact with hardware PCF. | P3 |
| Command-buffer timeout (5 s) | Crash | Appears when the GPU is saturated by other apps; Metallum raises "5s timeout reached when waiting for Metal submit completion". Not a translation problem; retry. | P3 |

## 4. Render pipeline (not GLSL)

| Item | State | Blocks | Prio |
|---|---|---|---|
| Failure of a pipeline Sodium or the sky builds crashes the frame instead of putting the pack aside | Open | Mellow, E-LITE, miniature | P1 |
| Held item drawn before the deferred stage (so Photon lights it) | Written, unreliable, off by default | Photon hand | P1 |
| `gbuffers_damagedblock`, `gbuffers_line`, `gbuffers_entities_glowing`, `shadow_water` | Not run (game's own shader draws them) | cosmetic | P3 |
| `renderStage` uniform | Partly set | BSL/Complementary small effects | P3 |
| Entity and block-entity ids from `entity.properties` | Zero for entities | packs that tint by entity | P3 |
| `mc_midTexCoord`, `at_tangent`, `at_midBlock`, PBR atlases | Approximate / absent | PBR packs | P3 |

## 5. Visible bugs of packs that already load

These are not translation gaps but wrong output; each needs a capture of the buffers (the debug properties `metallumextra.debugBuffer` and `debugAfter` exist) to find the first wrong pass.

| Pack | Symptom |
|---|---|
| Sildur's Vibrant | World black, HUD fine |
| MakeUp UltraFast | Almost black at midday |
| BakedPotato | White blotches over terrain |
| OPAL | Sky blown out to white |
| Sildur's Basic | Red-tinted sky |
| Photon | Darker and greener than the reference; hand not lit |

## Suggested order

1. Dummy for unbound samplers (Mellow, Velocity, E-LITE) and "put the pack aside" for Sodium/sky pipeline failures: removes all three crashes.
2. `shadow` for composite/deferred (Bliss, RedHat), duplicate `projectionMatrix` (Noble), `gl_Fog` rest (TAA Project), `#version` < 140 sky (miniature).
3. Find the dark/black outputs (Vibrant, MakeUp), since a pack that loads but draws wrong is worse than one that is refused.
4. `image.*`, vertex `imageStore`, atomics: Photon's coloured-lights profile.
5. Integer formats, geometry shaders, `random`, unary minus.
