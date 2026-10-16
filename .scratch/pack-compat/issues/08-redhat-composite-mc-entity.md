# 08 — Composite programs declaring terrain vertex inputs fail to build (RedHat)

Status: ready-for-agent

## Symptom
RedHat: `Shader pack RedHat_v34.3.1.zip is broken and is switched off: Failed to find or
load pipeline metallum-extra:pipeline/standard/33/composite1`, caused by
`ShaderCompileException: vertex shader ... attrib (mc_Entity) does not have a matching
vertex buffer element`. In-game log 16:38:20-22. Pack otherwise plans fine (15 programs,
8 buffers).

## Root cause (confirmed in-zip)
RedHat's `shaders/program/composite1.glsl:746` (shared by all dimensions incl. End) has
`attribute vec4 mc_Entity;` inside a COMPOSITE (fullscreen-quad) program. Native
Iris/OpenGL links unbound attributes fine (they read 0); RenderPearl/Metal is strict and
refuses to build.

## Fix direction
Fullscreen/composite adaptation must strip terrain-only vertex inputs (`mc_Entity`,
`vaPosition`, …) or `#define` them to constants — the same treatment `IrisWorldAdapter`
gives world programs. Audit: any `in`/`attribute` in a composite/deferred/compute-stage
vertex shader for names that only exist on terrain pipelines.

## Done when
RedHat composite1 builds; pack stays on in all three dimensions.
