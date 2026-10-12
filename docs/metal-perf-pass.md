# Metal performance pass (branch `metal-perf-pass`)

Scope: the "General Metal Performance Optimizations" list, vanilla (shaders off) first. Shader-side items are deferred until shader support is complete.

Every item ends in one of these states:

- **Already in Metallum**: checked in the Metallum 0.0.25 bytecode (`com.metallum.render`), nothing to do.
- **Measured, rejected**: a measurement shows no worthwhile gain.
- **Not started**: needs a render graph, a Metallum backend change, or a Sodium draw-path change. Reason given.
- **Deferred (shaders)**: touches shader code, which waits for shader support to be complete.
- **Not tested**: no measurement yet.

Metallum is a third-party mod (kokodio/metallum). Its backend is only reachable through mixins and the `com.metallum.render` bridge. Anything that changes how Metallum encodes passes or allocates heaps is a Metallum change, not a metallum-extra change.

## Baseline (existing data)

From `perf-test/results/report.md` (vanilla, no shader pack, Metal):

- 1,545 FPS average, median frame 0.58 ms, p99 1.47 ms.
- Main-thread CPU 0.24 ms per frame.
- GPU device utilization 97%.

The vanilla scene is GPU-bound with CPU headroom. CPU-side submission work (batching, parallel encoding, argument buffers, Java/native crossing) is therefore unlikely to show a gain here.

## Measured

### Pipeline binary archive (section 16, "Metal pipeline caching") — rejected

Instrumentation added this pass: `MetalUtilitiesMixin` wraps `MetalUtilities.newLibrary` and `MetalUtilities.newRenderPipelineState` and reports to the resource log (`pipeline-library`, `pipeline-state`).

One dev-client launch, world created and left for 25 s (`run/metallum-extra/resources-2026-10-12_14-30-25.log`):

| Category | Count | Total ms | Mean ms | Max ms |
|---|---:|---:|---:|---:|
| pipeline-library (MSL to Metal library) | 166 | 15.0 | 0.09 | 4.9 |
| pipeline-state (library to pipeline state) | 213 | 26.8 | 0.13 | 4.6 |
| pipeline-translate (GLSL, SPIR-V, MSL cache hits) | 205 | 723.7 | 3.53 | 572.7 |

Metal's own compile work totals about 42 ms per launch. A `MTLBinaryArchive` can save at most that, so it is not worth the change. The translate step is the larger cost. Excluding the one 573 ms outlier, it averages about 0.7 ms per pipeline (about 150 ms per launch), and most of that is already served from the disk translation cache. Open question: what the 573 ms outlier is (one-time warm-up on a worker thread, first in the log). It is not on the render thread, so it is not pursued here.

Caveat: this was one run. The run window's focus state was not controlled. These are CPU compile timings, so they should not depend on focus, but FPS numbers do (see the focus note in the session).

### Redundant state binds (section 3A, 16) — already in Metallum

`MetalRenderPass` keeps dirty flags (`pipelineDirty`, `vertexBuffersDirty`, `scissorDirty`, `pushConstantsDirty`) and only rebinds when they change. `MetalCommandEncoder.invalidateEncoderState` resets them at encoder boundaries.

### Texture usage and compression (section 4C) — already in Metallum

`MetalGpuTexture` maps usage through `toMtlTextureUsage`, which only ever sets `ShaderRead` (1) and `RenderTarget` (4). It never sets `ShaderWrite` (2) or `PixelFormatView` (16). Those two are what disable Apple's lossless texture compression. Storage is `Private` and hazard tracking is `Untracked`, so nothing extra to change.

## Already in Metallum

| Section | Item | Evidence |
|---|---|---|
| 4B | Triple-buffered dynamic data | `MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT = 3`; `MetalTransientMemory` ring with `GpuBlock` allocator; `MetalDestructionQueue` for deferred release |
| 4C | Texture usage / compression / storage | `toMtlTextureUsage` (above); `setStorageMode(Private)`; `setHazardTrackingMode(Untracked)` |
| 3A, 16 | Redundant pipeline and binding elimination | dirty flags in `MetalRenderPass` (above) |
| 6C (part) | Load actions | `MTLLoadAction.Clear` / `Load` / `DontCare` chosen per pass |

## Not started

| Section | Item | Reason |
|---|---|---|
| 2 | GPU-driven chunk rendering, ICBs | Sodium owns the terrain draw path; Metallum uses no `MTLIndirectCommandBuffer`. CPU has headroom (see baseline), so low expected gain for vanilla. Not tested. |
| 2 | GPU frustum / occlusion culling | Same as above. |
| 3B | Parallel command encoding | Metallum opens one encoder per pass; no `MTLParallelRenderCommandEncoder` in the bytecode. Would need an encoder rewrite. |
| 3C | Java-to-native overhead | Main-thread CPU is 0.24 ms per frame, so little to gain. Not measured directly. |
| 4A | Persistent chunk vertex and index buffers | Sodium already keeps region buffers; not investigated further. |
| 5 | Argument buffers / bindless | `metal-jvm` has `MTLArgumentEncoder`; Metallum binds each resource (`setFragmentTexture`, `setFragmentBuffer`). Gain depends on bind CPU cost, which is small here. Not tested. |
| 6A | Merging compatible passes (imageblocks, programmable blending) | Needs dependency information between passes; there is no render graph. |
| 6B | Memoryless attachments | No `memoryless` in the bytecode. Needs consumer analysis per attachment. |
| 6C | Store actions `DontCare` | All stores are `Store` (three `setStoreAction` sites). `DontCare` is only safe if no later pass reads the attachment; that needs a render graph. Not done for correctness risk. |
| 7 | Resource aliasing with `MTLHeap` | No `MTLHeap` in Metallum. Benefit is memory only; RSS is flat at about 2.5 GB in the baseline. Not tested. |
| 8 | Command-buffer boundaries and sync | Vanilla has no compute, so the compute-boundary part does not apply. Rest not analyzed. |
| 13 | Metal 4 pipeline / compilation API | Metallum is on the Metal 3 API. Migration is a Metallum backend change. |
| 14 | Sparse textures and streaming | No measured vanilla memory pressure. Not tested. |

## Deferred (shaders)

| Section | Item |
|---|---|
| 9 | FP16 shader arithmetic |
| 10 | Register pressure and loop unrolling |
| 11 | Compute threadgroup sizing, threadgroup memory, SIMD-group ops |
| 15 (shader-owned formats) | Format changes for pack-declared buffers |

## Not tested

| Section | Item | Why it is not measured yet |
|---|---|---|
| 12 | Depth prepass, early-Z draw ordering | Needs a change to Sodium's draw order for opaque and cutout terrain. |
| 15 (vanilla intermediates) | Format audit for vanilla render targets | Not audited. |
| 19 | Extended benchmark scenes | `perf-test` has the scenes; no new ones added. |

## Not done here (roadmap items)

- 1 (render graph): needed before 6A, 6B, 6C and 7 can be tested safely. It is the single biggest blocker.
- 17 (architecture) and 18 (roadmap): documentation items. This file is the status list.

## Open decisions

1. The large items (render graph, ICBs, GPU culling, parallel encoding) all live in Metallum's backend. Doing them means either a patched Metallum fork or a bridge into Metallum internals. That is a separate decision.
2. Vanilla is GPU-bound. The GPU-side candidates (6A to 6C, 7, 12) need a per-pass GPU profile first, to see whether bandwidth or ALU dominates. The existing `GpuTimed` reports whole command buffers only, so per-pass timing needs new counter-sample instrumentation.
