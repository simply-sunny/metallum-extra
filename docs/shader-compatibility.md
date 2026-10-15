# Shader pack compatibility

What Metallum Extra does with the shader packs in the *Metallically Vibrant* OneClient instance (26 packs), tested on 2026-10-11
with Metallum Extra 0.2.0 / Metallum 0.0.25 / Minecraft 26.3, Apple Silicon, 1708x960, clear weather, midday.

How it was tested: a debug script (`-Dmetallumextra.debugScript=...`) loads a test world, picks the pack, waits 14 s, measures frame
times for 8 s (`perf`) and takes a screenshot. Each pack ran in a fresh game start with the saved pack reset to *builtin*.
"Reason" columns are the mod's own log lines. An offline check (`./gradlew compileCheck -Pzip=<pack>`) compiles every program of a pack
without starting the game; it only covers the overworld unless `-Pdimension=world-1` or `world1` is given.

## Summary

| Result | Packs |
|---|---|
| **Works** | BSL v10.1.8, Complementary Unbound r5.9.3, Photon v1.3b (default profile), b&w shader, Sildur's Basic v2.7, OPAL v1.2.1 |
| **Loads and draws, with visible problems** | MakeUp UltraFast 9.5g (very dark at midday), BakedPotato (white blotches over the terrain), Sildur's Vibrant v2.02 (loads, the world is black) |
| **Loads, then is switched off on the first frame** (a pipeline fails to compile) | Noble v1.9.9, Velocity |
| **Crashes the game** | Mellow v3.4.1a, E-LITE 5.1.3, miniature-shader 2.19 |
| **Refused when chosen** (message in chat, game keeps running with shaders off) | Bliss, Bloop, I Like Vanilla, Just Colored Lighting, RedHat, TAA Project |
| **Not usable at scan time** | iterationT, RenderPearl, The Better Default |
| **Not shader packs** (vanilla-style core-shader resource packs) | Energy Shaders, Night Vision Shaders, Smoother Glowing |

Earlier today only five packs worked and eleven were refused. Supporting `fmod` and the things behind it (below) moved
Sildur's Basic, OPAL, MakeUp, Vibrant, E-LITE and Complementary Unbound forward.

## Per pack

### Works

| Pack | Programs / buffers | Frame time (median) | Notes |
|---|---|---|---|
| BSL v10.1.8 | 25 / 6 colour, 154 MB | 5.8 - 9.7 ms | Shadows, sky, water, hand all correct. `gbuffers_damagedblock`, `gbuffers_entities_glowing` not run (cracks and glow outlines use the game's own shader). |
| Complementary Unbound r5.9.3 | 27 / 9, 219 MB | 8.2 - 11.7 ms | Correct. Also not run: `gbuffers_line`. (Complementary Reimagined r5.5.1 also works; Reimagined r5.9.3 has more than 8 attachments and is not supported.) |
| Photon v1.3b | 44 / 15, 231 MB | about 21 ms best case (48 fps) | Shadows, sky, clouds, water, compute-built sky lighting. Not compared with the reference; darker and greener than expected. The held item is drawn after the composite programs, so Photon does not light it (see "Known problems"). Photon's coloured-lights profile does not work (needs `image.*`). Not run: `gbuffers_damagedblock`, `gbuffers_line`, `shadow_water`. |
| Sildur's Basic v2.7 Fancy | 7 / 3, 32 MB | about 6 ms | World and hand render; the sky has a red tint (not compared with the reference). Needed: `fmod`, `gl_FogFragCoord`, `CAT_*` constants. |
| OPAL v1.2.1 | 23 / 7, 148 MB | 12.8 ms | World, shadows and hand render; the sky is blown out to white. Needed: a dummy for a varying the vertex shader does not write. |
| b&w shader | 1 / 1, 6 MB | 1.6 - 2.4 ms | Correct (a single final program). |

### Loads and draws, with visible problems

| Pack | What is wrong |
|---|---|
| MakeUp UltraFast 9.5g | Loads (22 programs) and runs at 5 - 10 ms, but the picture is almost black at midday; lighting values are wrong somewhere (not diagnosed). Needed: `fmod`, `blockEntityId` as a vertex input, `#ifdef NAME // comment`. |
| BakedPotato | Large white blotches over the terrain (a smeared cloud or fog layer). Not diagnosed. |
| Sildur's Vibrant v2.02 | All 21 programs compile and the pack loads, but the world is drawn black (HUD is fine). Not diagnosed. Needed: `fmod`, `gl_FogFragCoord`, directives inside `/* */` comments (its Nether and End programs), a missing vertex varying. |

### Loads, then switched off on the first frame

| Pack | Reason |
|---|---|
| Noble v1.9.9 | The shadow pipeline fails: *'#define' : Macro redefined; different substitutions: projectionMatrix* (the pack defines `projectionMatrix` itself and so does the shadow prelude). |
| Velocity | The sky pipeline (`celestial_stage4`) fails with *Unable to find shader defined uniform (colortex6)*. |

### Crashes

| Pack | Reason |
|---|---|
| Mellow v3.4.1a | Terrain pipeline fails with *Unable to find shader defined uniform (colortex6)*; Sodium builds that pipeline outside the code that puts a failing pack aside, so the frame crashes. |
| E-LITE 5.1.3 | Same kind of crash: the terrain pipeline fails with *Unable to find shader defined uniform (shadowtex0)* (the program declares a sampler the pipeline does not bind). |
| miniature-shader 2.19 | `gbuffers_skybasic` is `#version` below 140, which the Vulkan SPIR-V compiler rejects; the sky pipeline then crashes with *Render pass had debug groups left open*. |

All three crashes are bugs in the mod: a failing pipeline should put the pack aside, as happens for Noble and Velocity.

### Refused when chosen

| Pack | Reason shown |
|---|---|
| Bliss v2.1.2, RedHat v34.3.1 | `deferred` / `composite` reads `shadow` (the shadow map sampler without `shadowtex*`), which is not provided to composite programs. |
| TAA Project | `gl_Fog` (the whole fog structure; only `gl_Fog.start/end/scale` and `gl_FogFragCoord` are translated). |
| I Like Vanilla v1.5.0b | `prepare1` reads `tex` (the legacy texture sampler name), not provided in the prepare stage. |
| Just Colored Lighting 1.0.2 | `gbuffers_basic` reads `cSampler3_colored_light`, a custom sampler that the programs drawing the world cannot be given. |
| Bloop 1.8.0-Alpha-3 | `variable.vec3.moonVector = -sunVector`: a unary minus on a vec3 in a custom variable expression is not supported. |

### Not usable at scan time

| Pack | Reason |
|---|---|
| iterationT 3.2.0 | `gbuffers_skytextured` has a geometry shader (not supported). |
| RenderPearl v2.8.0-beta.4 | None of its programs can run in this version (it has none). |
| The Better Default | `composite1.fsh` exists without `composite1.vsh` (Iris accepts that; the mod requires both). |

### Not shader packs

Energy Shaders, Night Vision Shaders and Smoother Glowing replace the game's own `core` shaders (`assets/minecraft/shaders/core/*`) and have no `shaders/` folder in the Iris sense. Metallum Extra does not load them as shader packs; use them as resource packs.

## Performance

Frame times are the debug script's `perf` over 8 seconds, taken on a machine that was also compiling other code and running other apps. They vary by a factor of 2 between batches for the same pack (in one batch every run was throttled to 30 fps and none was usable). Treat them as orders of magnitude; the ranges above are the spread over the batches in which the pack really ran.

| Setup | Median |
|---|---|
| No shaders | 1.0 - 3.7 ms |
| Built-in "Metallically Beautiful" | 5.4 - 7.6 ms |
| BSL, Complementary Unbound, OPAL, Vibrant | 6 - 13 ms |
| Photon (high profile) | about 21 ms at best |

Photon is about three times as expensive as BSL: 15 colour buffers (231 MB at this size), compute-built sky lighting, 44 programs, 2048 px shadow map.

## What changed today in the loader (and which packs it helped)

| Change | Helped |
|---|---|
| `fmod`, `torad`, `todeg` in custom uniform expressions | Sildur's Basic, Vibrant, MakeUp, E-LITE |
| `gl_FogFragCoord` translated to a varying | Sildur's Basic, Vibrant, MakeUp, E-LITE |
| `#ifdef NAME // comment` no longer decided wrongly | MakeUp, many others |
| Option value lines like `0.75// [...]` keep their `//` | E-LITE |
| Directives inside `/* */` are not read; block comments are blanked before analysis | Vibrant (Nether, End), Photon |
| `uniform sampler2D a, lightmap;` lists, `dh*` uniforms zero-filled | E-LITE |
| `attribute int blockEntityId` read as 0 | MakeUp |
| Biome category constants `CAT_*` | Sildur's Basic |
| Vertex shader that never writes a varying the fragment shader reads gets a dummy output | OPAL, Vibrant |

## Known problems that affect more than one pack

1. **Held item and Photon.** Photon lights the hand in its deferred stage, before the game draws the hand. The mod can draw the hand early (`-Dmetallumextra.earlyHand=true`) and Photon then lights it, but the item was missing or in the wrong place in some frames, so it is **off by default**. With it off the hand is drawn after the composite programs: correct for BSL, Complementary and the built-in shader, not lit by Photon.
2. **Failures should put a pack aside, not crash.** Sodium's terrain pipeline (Mellow, E-LITE) and the sky pipeline (miniature) fail outside the code that catches a pack's failure.
3. **Samplers a program declares but the pipeline does not bind** (`colortex6` in Mellow, Velocity; `shadowtex0` in E-LITE): bind a dummy or drop the declaration.
4. **A pack that crashes the game is chosen again on the next start.** Set `shaders.pack=builtin` in `config/metallum-extra.properties` to recover.
5. **The game can fail with "5s timeout reached when waiting for Metal submit completion"** when the machine's GPU is heavily used by other apps; it passes on a retry. It is not a pack problem.

## Cheap wins, in order

1. Catch pipeline-compile failures for Sodium terrain and the sky pass and put the pack aside (Mellow, E-LITE, miniature).
2. Bind a dummy sampler for declared-but-unbound samplers (Mellow, Velocity, E-LITE).
3. Provide `shadow` to composite/deferred programs (Bliss, RedHat); translate the rest of `gl_Fog` (TAA Project).
4. Remove a duplicate `projectionMatrix` define in the shadow prelude (Noble).
5. Fix `#version` below 140 in the sky path (miniature).
6. Find out why Vibrant is black, MakeUp dark, BakedPotato blotchy, OPAL's sky white, Basic's sky red.
7. Reliable early hand for Photon.

## How to repeat

```
for each pack:  JAVA_TOOL_OPTIONS="-Dmetallumextra.debugScript=<script>" ./gradlew runClient
script: world Test / wait 8000 / cmd ... / pack <file.zip> / set shaders on / wait 14000 / perf 8000 <name> / shot <name> / quit
```

Do not name the script like the screenshot: `shot <name>` writes `<name>.txt` next to the script. Logs are the game's lines starting `[Metallum Extra] Shader pack ...`.
