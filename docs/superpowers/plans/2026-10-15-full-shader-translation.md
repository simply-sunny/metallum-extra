# Full Shader Translation & Track 1 Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete 100% shader pack compatibility for Metallum Extra across all 24 supported shader packs in the test corpus by resolving the remaining GLSL/loader translation gaps and implementing Track 1 Storage Images & SSBO runtime bindings.

**Architecture:** 
1. Fix translation edge cases in `LegacyGlsl` (`textureLod` on shadow samplers), `IrisWorldAdapter` (legacy `#version 120` sky shaders), and `CustomUniforms` (vector types `vec3`, vector arithmetic, component access, unary minus).
2. Unify compute and raster storage resources in `IrisPlan` into a single frame-level pool.
3. Rewrite storage image bindings to `70 + i` (well within Metal's 128 texture slots) and SSBO buffer bindings to `20 + i` (safely below Metal's 31-buffer per-stage limit, leaving slot 30 for `SPIRV-Cross` buffer size constants).
4. Implement resource allocation, clear lifecycles (`clear = true/false`), and encoder binding across vertex, fragment, and compute stages.

**Tech Stack:** Java 25, Fabric Loader / Loom, Vulkan SPIR-V (Shaderc / glslang), SPIRV-Cross, Metal Shading Language (MSL 4.0), LWJGL, JUnit/PackTests.

**Spec:** `docs/opengl-to-metal-remaining.md`, `docs/shader-compatibility.md`, `docs/translation-table.md`.

## Global Constraints

- Never use buffer slot indices >= 30: Apple Silicon Metal buffer argument table limit is strictly 31 (`0..30`), with slot 30 reserved for SPIRV-Cross `spvBufferSizeConstants`. Place SSBOs strictly at `binding = 20 + i`.
- Place storage images at `binding = 70 + i` (`[[texture(70+i)]]`), taking advantage of Metal's 128 texture slots (`0..127`).
- Preserve English only (`en_us`) for any user-facing text or options; do not edit non-English translations.
- Honour `<clear>` semantics: only clear images/buffers every frame when `clear = true`. Keep contents across frames when `clear = false` (critical for Photon's temporal voxel floodfill).
- Maintain backward compatibility with all 336 existing checks in `PackTests`.

## Review Focus

1. **Unsized runtime SSBO arrays:** `SPIRV-Cross` reserves `[[buffer(30)]]` for `.length()` queries; ensure no SSBO binding collides with slot 30.
2. **Matrix member and vector expression precedence in CustomUniforms:** `matrix.0.0` and `vec3 * vec3` element-wise multiplication parsed correctly.
3. **Stage usage tracking:** `bindDrawState` must only bind image/buffer slots that the active vertex/fragment shader actually references.
4. **Hazard tracking:** Ensure allocated storage textures/buffers use `MTLResourceHazardTrackingModeTracked` so Metal automatically synchronizes vertex writes before composite reads.
5. **Texture formats:** Ensure storage images request valid pixel formats with `MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite`.

---

### Task 1: Fix `textureLod` on Shadow Samplers in `LegacyGlsl`

**Files:**
- Modify: `src/main/java/com/metallumextra/shader/pack/LegacyGlsl.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] Write a test in `PackTests.java` verifying that `textureLod(shadowtex1, sampleCoord, 0.0)` with a declared `sampler2DShadow shadowtex1;` is rewritten to `mx_shadowCompare(shadowtex1, sampleCoord)` without invalid overload errors.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and verify failure.
- [ ] In `LegacyGlsl.java:shadowSamplers`, update the regex pattern replacing `texture(...)` calls on shadow samplers to also match `textureLod(name, coord, lod)`:
  ```java
  text = text.replaceAll("\\btexture(?:Lod)?\\s*\\(\\s*" + name + "\\s*,\\s*([^,]+)(?:\\s*,\\s*[^)]+)?\\)", "mx_shadowCompare(" + name + ", $1)");
  ```
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and `./gradlew -I local-testing.init.gradle compileCheck -Pzip="run/shaderpacks/iterationT 3.2.0.zip"` to verify `iterationT` compiles.
- [ ] Commit changes: `git commit -am "Translate textureLod calls on shadow samplers to mx_shadowCompare"`.

---

### Task 2: Adapt Sky Shaders with Legacy GLSL Support

**Files:**
- Modify: `src/main/java/com/metallumextra/shader/IrisWorldAdapter.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] Write a test in `PackTests.java` passing a `#version 120` sky shader through `IrisWorldAdapter.adaptSky` and verifying it is translated to `#version 330 core` with standard inputs and varying declarations.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and verify failure.
- [ ] In `IrisWorldAdapter.java:adaptSky`, detect if `LegacyGlsl.isLegacy(source)` is true, and if so, run `LegacyGlsl.translate(source, ...)` before applying the sky prelude.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and verify `miniature-shader-2.19.zip` passes.
- [ ] Commit changes: `git commit -am "Support legacy GLSL translation in adaptSky"`.

---

### Task 3: Vector Expressions & Unary Minus in `CustomUniforms`

**Files:**
- Modify: `src/main/java/com/metallumextra/shader/pack/CustomUniforms.java`
- Modify: `src/main/java/com/metallumextra/shader/pack/IrisUniforms.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] Write tests in `PackTests.java` for:
  - `vec3(a, b, c)` constructor expression.
  - Vector element-wise operations: `vec3 * vec3`, `vec3 + vec3`, `vec3 * scalar`.
  - Component swizzling/indexing: `pos.x`, `pos.y`, `pos.z`, `pos.r`, `pos.g`, `pos.b`.
  - Matrix indexing: `gbufferModelViewInverse.0.0`, `gbufferModelViewInverse.0.x`.
  - Unary minus on vector: `-sunVector`.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and verify failure.
- [ ] Extend `CustomUniforms.Node` to evaluate either `double` scalar or 3-component `double[]`/`Vector3d`, supporting vector math and matrix component extraction.
- [ ] Run `./gradlew -I local-testing.init.gradle packCheck -Pzip="run/shaderpacks/RenderPearl v2.8.0-beta.4.zip"` and verify RenderPearl plans successfully.
- [ ] Commit changes: `git commit -am "Support vector expressions, constructors, and unary minus in CustomUniforms"`.

---

### Task 4: Frame-Level Unified Resource Registry for Storage Images and SSBOs

**Files:**
- Modify: `src/main/java/com/metallumextra/shader/pack/IrisPlan.java`
- Modify: `src/main/java/com/metallumextra/shader/pack/PropertiesFile.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] Write a test in `PackTests.java` verifying that:
  - `image.<name>` properties with pixel formats (`rgba16f`, `r32ui`, etc.) and `clear` flags are registered at the frame level.
  - `bufferObject.<index>` SSBO properties are parsed and tracked.
  - Built-in aliases `colorimg0..5` -> `colortex0..5` are created automatically.
  - A raster pass (`.vsh`/`.fsh`) and a compute pass (`.csh`) referencing the same image name share the exact same resource ID.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and verify failure.
- [ ] Implement frame-level `IrisPlan.StorageRegistry` storing `StorageImage` and `StorageBuffer` definitions.
- [ ] Expose unified lookup methods `image(String name)` and `buffer(int index)` on `IrisPlan`.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest`.
- [ ] Commit changes: `git commit -am "Unify compute and raster storage image and SSBO registry in IrisPlan"`.

---

### Task 5: GLSL & SPIRV-Cross Binding Injection (`70+i` for Images, `20+i` for SSBOs)

**Files:**
- Modify: `src/main/java/com/metallumextra/shader/GlslLocations.java`
- Modify: `src/main/java/com/metallumextra/shader/pack/LegacyGlsl.java`
- Modify: `src/main/java/com/metallumextra/shader/ShaderSources.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] Write tests in `PackTests.java` checking that:
  - `layout(...) uniform image2D img;` and `uimage3D` declarations in `.vsh`, `.fsh`, and `.csh` receive `layout(..., binding = 70 + i)`.
  - `layout(std430) buffer Name { ... };` declarations receive `layout(std430, binding = 20 + i)`.
  - Program metadata tracks bitmasks of used image slots (`70..127`) and SSBO slots (`20..29`) per vertex and fragment stage.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest` and verify failure.
- [ ] In `GlslLocations.java` and `ShaderSources.java`, inject the calculated binding indices into image and SSBO declarations and record stage usage masks.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest`.
- [ ] Commit changes: `git commit -am "Inject binding = 70+i for storage images and 20+i for SSBOs"`.

---

### Task 6: Native Allocation, Clear Lifecycle, and Usage Flags

**Files:**
- Modify: `src/main/java/com/metallumextra/shader/PackTextures.java`
- Modify: `src/main/java/com/metallum/render/MetallumExtraNative.java`
- Modify: `src/main/java/com/metallumextra/shader/IrisTargets.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] Write a test in `PackTests.java` verifying that storage textures are allocated with `MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite` (and `MTLTextureUsageRenderTarget` for `colorimg` aliases) and tracked hazard mode.
- [ ] Implement texture and buffer allocation in `PackTextures.java` and `IrisTargets.java` backed by `MetallumExtraNative`.
- [ ] Implement the frame clear pass: textures/buffers with `clear = true` are cleared on frame begin; textures/buffers with `clear = false` retain contents across frames.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest`.
- [ ] Commit changes: `git commit -am "Implement storage texture and SSBO buffer allocation with clear lifecycle"`.

---

### Task 7: RenderPass & DrawState Binding with Stage Usage Bitmasks

**Files:**
- Modify: `src/main/java/com/metallumextra/mixin/MetalRenderPassMixin.java`
- Modify: `src/main/java/com/metallumextra/shader/IrisPipeline.java`
- Modify: `src/main/java/com/metallum/render/MetallumExtraBridge.java`
- Test: `src/test/java/com/metallumextra/PackTests.java`

- [ ] In `IrisPipeline.java`, store the active program's stage storage binding bitmasks (vertex image mask, fragment image mask, vertex SSBO mask, fragment SSBO mask).
- [ ] In `MetalRenderPassMixin.java` (`bindDrawState`), read the active pipeline's masks and bind:
  - Vertex stage: `setVertexTexture(tex, 70 + i)` and `setVertexBuffer(buf, 0, 20 + i)`.
  - Fragment stage: `setFragmentTexture(tex, 70 + i)` and `setFragmentBuffer(buf, 0, 20 + i)`.
- [ ] Add explicit memory barriers only when the same render pass performs vertex writes and subsequent fragment reads on the same resource.
- [ ] Run `./gradlew -I local-testing.init.gradle packTest`.
- [ ] Commit changes: `git commit -am "Bind storage images and SSBOs in bindDrawState using stage bitmasks"`.

---

### Task 8: Synthetic 3-Primitive Test Pack & Full Matrix Verification

**Files:**
- Modify: `src/test/java/com/metallumextra/TestPacks.java`
- Modify: `src/test/java/com/metallumextra/PackTests.java`
- Modify: `docs/shader-compatibility.md`
- Modify: `docs/opengl-to-metal-remaining.md`

- [ ] In `TestPacks.java`, build a minimal synthetic shader pack `Track1StorageTest.zip`:
  - `gbuffers_terrain.vsh`: writes `imageAtomicAdd` on `r32ui` `uimage2D` (`binding = 70`), `imageStore` on `rgba8` `image2D` (`binding = 71`), and `atomicAdd` on an SSBO counter (`binding = 20`).
  - `composite.fsh`: reads `imageLoad` and SSBO counter, outputs green (0, 1, 0, 1) to `colortex0` if non-zero values match, or red (1, 0, 0, 1) otherwise.
- [ ] Add end-to-end check in `PackTests.java` running compilation and plan validation on `Track1StorageTest.zip`.
- [ ] Run `./gradlew -I local-testing.init.gradle compileCheckAll -Pfolder=run/shaderpacks` across all 24 packs and verify 100% compilation across all shader packs.
- [ ] Update `docs/shader-compatibility.md` and `docs/opengl-to-metal-remaining.md` to reflect 100% translation status.
- [ ] Commit changes: `git commit -am "Add Track 1 synthetic test pack and verify full shader corpus compatibility"`.
