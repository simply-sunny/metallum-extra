# Architecture

Metallum Extra is a Fabric client mod for macOS. It sits on top of
[Metallum](https://modrinth.com/mod/metallum-mc), the Metal backend for Minecraft,
and does three things:

1. **Fixes frame pacing** on Apple Silicon (unlocked FPS without stutter).
2. **Runs shader packs** written in the Iris layout, on Metal.
3. **Measures** where frames go, so performance changes can be proven.

This document explains how those parts fit together. For what the mod does for
players, read [details.md](details.md). For writing packs, read
[shader-packs.md](shader-packs.md).

---

## At a glance

```
            Minecraft 26.3 (renderpearl)              Sodium          Mod Menu
                       │                                │                │
                       ▼                                ▼                ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ Metallum (0.0.25)  — Metal backend, package com.metallum.render              │
└──────────────────────────────────────────────────────────────────────────────┘
          ▲  mixins (macOS only, see ExtraMixinPlugin)       ▲ bridge classes
          │                                                  │
┌─────────┴──────────────────────────────────────────────────┴─────────────────┐
│ Metallum Extra                                                               │
│                                                                              │
│  Frame pacing    NonBlockingPresent · fast section recenter · spread cleanup │
│  Shader pipeline PackManager → ShaderPack → ProgramSet/IrisPlan → IrisPipeline│
│  Metal extras    compute · geometry kernel · MetalFX upscale · 3D textures   │
│  Measurement     FrameProfiler · HitchSampler · GpuTimed · RunLog · ResourceLog │
│  Settings        ExtraConfig · Settings · PackOptions · Mod Menu / Sodium UI │
└──────────────────────────────────────────────────────────────────────────────┘
```

The mod is one jar: `metallum-extra-<version>.jar`. It has no native code of its
own beyond what Metallum already ships.

---

## Layers

| Layer | Package | Job |
|---|---|---|
| Entry and config | `com.metallumextra` | Fabric entrypoints, config file, settings screens |
| Frame pacing | `NonBlockingPresent`, `mixin/MetalUtilitiesMixin`, `mixin/RotatingSectionStorageMixin`, `mixin/SodiumNativeBufferMixin` | Remove the main-thread waits that Metallum and the game impose |
| Shader pipeline | `shader/`, `shader/pack/`, `shader/sodium/` | Load a pack, plan its passes, draw the frame with them |
| Metal extras | `com.metallum.render` (Metallum's package), `fx/` | Compute kernels, 3D textures, MetalFX upscaling |
| Measurement | `FrameProfiler`, `HitchSampler`, `GpuTimed`, `GcMonitor`, `FrameStats`, `RunLog`, `ResourceLog`, `mixin/MTL*` | Explain each stutter and each setting's cost |
| Development | `shader/DebugScript`, `shader/PipelineTrace`, `src/test`, `perf-test/` | Scripted runs, pipeline tracing, automated checks, A/B benchmarks |

---

## Why some code lives in Metallum's package

Metallum's classes are package-private. Mixins cannot name them, and Fabric
loads every mod with one class loader, so a class in `com.metallumextra` cannot
call them either.

The workaround is `com.metallum.render`, which lives **inside this jar** but in
Metallum's package:

- `MetallumExtraBridge` is the entry point for hooks into Metallum.
- `MetallumExtraCompute` encodes compute passes into the frame being recorded.
- `MetallumExtraNative` makes textures Metallum cannot make by itself (a 3D texture).
- `MetallumExtraUpscale` encodes the MetalFX scaler into Metallum's command buffer.

Keep this boundary small. Anything that does not need Metallum's internals belongs
in `com.metallumextra`.

---

## Frame pacing fixes

Each fix is a separate setting (see `ExtraConfig`) and can be turned off.

- **Non-blocking present** (`NonBlockingPresent`, `MetalUtilitiesMixin`).
  Metallum asks for a swapchain image on the render thread. With vsync off that
  call can block when macOS has no free image. The fix never waits for one.
- **Fast section re-centering** (`RotatingSectionStorageMixin`). Vanilla rebuilds
  the section storage each time the camera crosses a chunk section. The fix
  makes that cheap.
- **Spread Sodium cleanup** (`SodiumNativeBufferMixin`). Sodium empties its
  buffer-free queue all at once. The fix spreads that work over frames.

---

## Shader pipeline

This is the largest part of the mod. Read it top to bottom.

### 1. Find and load a pack

```
shaderpacks/*.zip  ─┐
shaderpacks/<dir>  ─┼─► PackManager ──► ShaderPack (ZipPack / folder)
built-in (jar)     ─┘                    │
                                         ├─ PropertiesFile   reads shaders.properties
                                         ├─ PackOptions      the player's option values
                                         ├─ PackProfiles     LOW / HIGH style presets
                                         └─ LoadCache        remembers a load until options change
```

- `PackManager` owns the list of packs and which one is in use.
- `ShaderPack` is a complete set of files in the Iris layout. A pack never reads
  outside its own folder.
- The **built-in pack** is an ordinary pack stored in the jar at
  `assets/metallum-extra/pack/shaders/`. It goes through the same loader as any
  player pack, so it is the best reference for what the loader supports.

### 2. Plan the passes

```
ShaderPack ──► ProgramSet (one per dimension) ──► IrisPlan
                                                   │
                  begin · shadow · prepare · gbuffers_* · deferred
                  · composite · final  ──►  an ordered list of passes,
                  each with the buffers it reads and writes
```

- `ProgramSet` pairs each program's `.vsh` and `.fsh`. A program with only half
  of its pair is dropped with a warning (see *Known risks* below).
- `IrisPlan` decides, once per load, which programs run in which stage, which
  `colortexN` buffers each one touches, and which uniforms it needs.
- `StandardOptions` finds the options a pack declares (`#define NAME v // [a b c]`).
- `PackScreens` and `PackOptionsScreen` turn those options into the settings menu.

### 3. Draw a frame

```
IrisPipeline.frame()
   ├─ IrisUniformValues    computes the standard Iris uniforms for this frame
   ├─ IrisTargets          colortex0–15 and the depth textures, sized to the screen
   ├─ ShadowPass           the world drawn again from the sun or moon, keeping depth
   ├─ IrisTerrain          terrain programs, using Sodium's vertex format and samplers
   ├─ IrisWorld            mobs, items, block entities, particles, weather, clouds
   │    └─ IrisWorldAdapter  makes a pack program fit one of the game's pipelines
   └─ deferred → composite → final passes, then the result is presented
```

Important points:

- **Iris uniforms are plain values.** `IrisUniforms` declares which ones a
  program may use. Metal has no loose uniforms, so they reach the shader through
  one uniform block.
- **Terrain is Sodium's.** `IrisTerrain` and `sodium/SodiumTerrain` keep Sodium's
  vertex layout and samplers. When a pack is loaded, Sodium's pipelines are made
  again with the pack's programs (`mixin/shader/SodiumTerrainPipelineMixin`).
- **Non-terrain draws are the game's.** The game's pipelines for entities, items
  and particles stay as they are. `IrisWorldAdapter` gives a pack program the
  inputs those pipelines provide.
- **Block identity** travels in the mesh. `BlockIds` and `BlockTypes` store a
  kind (water, plant, lamp, ...) in each vertex, so a pack can react to what a
  block is without a second lookup.
- **Compiling** goes GLSL → SPIR-V (shaderc) → Metal Shading Language
  (SPIRV-Cross) → Metal library → pipeline state. `TranslationCache` stores the
  MSL on disk, keyed by a SHA-256 of the input, so it is not made again.

### 4. Compatibility translation

Packs written for OpenGL do not compile unchanged on Metal. These classes fix that
in one place each:

| Class | What it fixes |
|---|---|
| `GlslLocations` | Adds the `layout(location)` numbers the game's Vulkan-style compiler requires |
| `LegacyGlsl` | Rewrites old `#version 120–150` code (`gl_Vertex`, `texture2D`, ...) |
| `GlslConditions` | Strips `#if` branches the loader does not need, so it can read the rest |
| `Substitutions` | Replaces what Metal cannot do with a safe equivalent (a varying the other stage never writes, a missing sampler) |
| `SamplerSlots` | Renumbers samplers when a shader uses more than the game's limit |
| `ShaderMacros` | Defines the names packs test with `#ifdef`, the way Iris does |

To see what the pipeline did for a pack that renders wrongly, run with
`-Dmetallumextra.trace=true`: `PipelineTrace` records the vertex formats and
pipeline fits, so a mismatch can be found before the game reports it. The
substitutions themselves are not logged.

### 5. Geometry and compute

Metal has no geometry stage and no storage binding for render pipelines, so:

- `GeometryKernel` runs a pack's vertex and geometry stages as one compute kernel.
  `GeometryStage` records what a geometry stage did for programs that do not
  have one.
- `ComputeCompiler` and `MetallumExtraCompute` run compute programs (`setup`,
  `shadowcomp`, and the `_a` stages). Compute binds storage images and buffers
  itself, which render pipelines cannot do yet.

### 6. Built-in shader

The built-in shader is the same loader and pipeline as any player pack.
`Quality` presets are values for `BuiltinOptions`; there is no separate code path.
Earlier versions had a second system that is now removed.

---

## MetalFX upscaling

`fx/Upscale` makes the world render into a smaller texture, then
`MetallumExtraUpscale` scales it up with Apple's spatial scaler. Menus and the HUD
stay at window size.

Metallum's textures are untracked and ordered by one Metal fence, so MetalFX's
own passes must signal and wait on events around them. Without that, the output
has tiles of garbage. This is handled in `MetallumExtraUpscale.encodeSpatial`.

---

## Measurement

Performance work here is done by measurement, not by feel.

| Tool | What it records | When it is on |
|---|---|---|
| `FrameProfiler` | Time the render thread spends in each Metallum wait point | `profiler.enabled` |
| `HitchSampler` | Stack samples of the render thread during a slow frame | With the profiler |
| `GpuTimed` | Metal's own GPU timestamps for each frame | With the profiler |
| `GcMonitor` | Stop-the-world GC pauses from JMX | With the profiler |
| `FrameStats` | Frame interval histogram with bounded memory | Always, cheap |
| `RunLog` | Every setting's value and frame times, one file per launch | On by default (see *Known risks*) |
| `ResourceLog` | Each pack load step and what it cost | Always |

Logs go to the game folder under `metallum-extra/`. `perf-test/` drives A/B
benchmarks: it creates several game instances, runs a fixed script in each,
and `analyze.py` compares the results.

---

## Configuration and state

| File | Owner | Contents |
|---|---|---|
| `config/metallum-extra.properties` | `ExtraConfig` | All mod settings; rewritten with every key so new options show up |
| `config/metallum-extra-packs.properties` | `PackOptions` | Each pack's option values, keyed by pack |
| `shaderpacks/` (instance folder) | `PackManager` | Player packs (ZIP or folder) |
| `metallum-extra/` (game folder) | `ResourceLog`, `RunLog` | Run and resource logs |
| `sodium-options.json` | Sodium | Read by `RunLog` to record Sodium's settings |

---

## Testing

| Command | What it checks | Needs the game? |
|---|---|---|
| `./gradlew architectureTest` | Pack loader and rewriting rules, frame stats, settings diff | No |
| `./gradlew -I local-testing.init.gradle packTest` | 61 headless pack checks | No |
| `./gradlew -I local-testing.init.gradle compileCheck` | Compiles the shaders with the shader compiler | No |
| `src/test/pack-regression/run.sh` | Plays the red/blue test pack in the game and checks screenshots | Yes |
| `src/test/iris-pipeline/run.sh` | Plays the Iris test packs with Metal's validation layer on | Yes |
| `src/test/baseline/run.sh` + `compareScenes` | Renders fixed scenes and diffs two renderers | Yes |

The headless tasks are plain programs, not JUnit, so Gradle's `test` task has
nothing to run.

---

## Branches

One repository holds the work that was previously in three checkouts.

| Branch | Purpose | Pushed to fork |
|---|---|---|
| `main` | Fork baseline for Minecraft 26.3 and Metallum 0.0.25 | Yes |
| `metal-perf-pass` | This architecture: shader pipeline, measurement, perf work | Yes |
| `shader-ui-first-pr` | Shader keyboard shortcuts and menu work | Already on fork |
| `voxy` | Voxy LOD terrain rendered through the Metal pipeline (work in progress) | Yes |
| `voxy-263` | Upstream Voxy source snapshot, the `263` branch. Reference only | **No**: the Voxy license forbids redistribution |
| `voxy-upstream-jar` | Upstream Voxy commit the local `libs/` jar was built from. Reference only | **No** |

Remotes: `origin` is `ohjey/metallum-extra` (upstream). `simply-sunny` is the
fork and is where work is pushed.

---

## Known risks

These were raised by an automated review of `metal-perf-pass`. They are not yet
verified or fixed.

- **`PackTextures`**: samplers named `mx:…` go to `builtin()`, which returns null
  for unknown names. A pack that declares an unknown `sampler3D` can then read a
  2D target, which fails at draw time.
- **`ProgramSet`**: a program with no matching `.vsh`/`.fsh`, or a geometry
  shader, is now dropped with a warning instead of failing the pack. A broken pack
  looks wrong rather than failing clearly.
- **`IrisWorldAdapter`**: `mx_entity_color` may be read by a fragment stage whose
  vertex stage never writes it. Metal leaves that value undefined.
- **`IrisPipeline.entityIdOf`**: throws if an entity type is not in the registry.
- **`ModelFeatureRendererEntityMixin`**: `preparingEntity` is cleared only on
  normal return, so an exception leaves it set.
- **`IrisTargets.createVolume`**: sizes are computed as `int`, so a large 3D image
  overflows. A failure after allocation also leaks the texture.
- **`RunLog`**: on by default. It reflects over every game option on the render
  thread, four times a second, and writes a new file each launch.
- **`GeometryKernel`**: used only by a test.
- **Working tree**: `AGENTS.md`, `CLAUDE.md` and `docs/agents/*` are deleted but
  still committed on `main`.

---

## Changing this

- Add a Metallum hook: put the class in `com.metallum.render` only if it needs a
  package-private member; otherwise use a mixin in `com.metallumextra.mixin`.
- Add a setting: define it in `Settings` (shown in both Sodium and Mod Menu) and
  read it from `ExtraConfig`.
- Add a pack feature: write it into `IrisPlan` or `IrisUniformValues`, and add a
  case to `architectureTest` or `packTest`.
- Updating Metallum: see *Updating to a new Metallum release* in
  [details.md](details.md).
