# OpenGL to Metal translation

Metallum Extra runs OptiFine/Iris shader packs, which are written in OpenGL GLSL, on Minecraft's Metal backend. Every program follows one path:
the pack's GLSL is rewritten to `#version 330 core` (`LegacyGlsl`, `IrisTerrain`, `IrisWorldAdapter`), compiled by glslang to SPIR-V, and
turned into Metal Shading Language by SPIRV-Cross. Compute programs take the same route to Metal kernels, dispatched between render passes.

Several differences between the two APIs are handled outside the shader text. Metal puts the origin at the top left and uses depth from
0 to 1, so passes are flipped and depth is converted. A render pass has at most eight colour attachments, so 8-bit buffers that only
the world writes are packed together. OpenGL treats an unbound vertex input as zero, while Metal refuses the pipeline, so inputs a program
cannot receive are replaced with constants. As in Iris, solid terrain is never alpha-tested, because Photon
keeps data in that channel. Draws that feed the deferred programs, such as entities and the held item, run
before those programs and not after.

The tables below show the state on 2026-10-16, with Metallum 0.0.25 on Minecraft 26.3. The first table covers each part of the translation.
The second lists the work that is left, by priority: P1 breaks packs that otherwise work, P2 blocks one or two packs, and P3 is polish.
The third counts the 27 test packs.

| Area | Done | Partial | Missing |
|---|---|---|---|
| GLSL syntax and names | legacy built-ins, `gl_Fog`, C++/MSL name clashes, custom uniforms | — | fixed-function leftovers (`gl_LightSource`) |
| Buffers and formats | 16 `colortex`, integer formats, packed 8-bit buffers | three-channel formats stored as four | — |
| Samplers and images | >16 samplers, 3D textures, shadow compare | `image.*` custom images | vertex `imageStore`, SSBOs, atomics |
| Pipeline stages | shadow, gbuffers, deferred, composite, compute, early hand | `renderStage`, entity ids | geometry shaders |

| Remaining work | Packs | Priority |
|---|---|---|
| Unbound sampler crashes a pipeline | Mellow, Velocity, E-LITE | P1 |
| `image.*`, vertex `imageStore`, atomics | Photon coloured lights | P2 |
| Geometry shader emulation | iterationT | P2 |
| `shadowcolor1`, `depthtex2` without hand | Complementary, BSL | P2 |
| Fast-math NaN behaviour | Photon sky | P2 |
| Damaged-block, line, glowing, `shadow_water` programs | all | P3 |

| Pack status | Count |
|---|---|
| Compile with zero failures | 22 |
| No `shaders/` folder in the zip | 3 |
| Engine mod, not a pack | 1 |
| Not tested (not on Modrinth) | 1 |

Packs that compile can still draw wrongly. Sildur's Vibrant, MakeUp and OPAL show dark or blown-out output, and each needs a buffer capture to find the first wrong pass.
