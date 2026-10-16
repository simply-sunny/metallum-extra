# 01 — Entity hurt overlay (red tint) missing on all Iris packs

Status: fixed

## Symptom
Hurting an enemy shows no red tint under any Iris pack. Metallum's internal shaders DO
show it, so the pipeline exists — Iris programs never reach it.

## Root cause
In Iris, `entityColor` is NOT a CPU-side std140 uniform. Native Iris's `EntityPatcher` transforms
`uniform vec4 entityColor;` into a varying (`out vec4 entityColor` in vertex, `in vec4 entityColor` in fragment)
and computes it from `texelFetch(Sampler1, UV1, 0)` in the vertex shader. In Metallum Extra, `entityColor`
was placed into the std140 uniform block with constant 0, so it was always `(0, 0, 0, 0)` in all fragment shaders.

## Fix
1. Removed `entityColor` from the std140 uniform block in `IrisUniforms.java`.
2. In `IrisWorldAdapter.java`:
   - Vertex shader injects `out vec4 entityColor;` and prepends to `main()`:
     ```glsl
     vec4 mx_ov = texelFetch(Sampler1, UV1, 0);
     entityColor = vec4(mx_ov.rgb, 1.0 - mx_ov.a);
     entityColor.rgb *= float(entityColor.a != 0.0);
     ```
   - In fragment shaders with overlay, `uniform vec4 entityColor;` is remapped to `in vec4 entityColor;` (matched by `GlslLocations.fit()`).
   - In fragment shaders without overlay, `uniform vec4 entityColor;` is remapped to `const vec4 entityColor = vec4(0.0);`.
3. Verified via unit tests in `PackTests.java`.
