# What is translated to Metal, and which shader needs it

Exploratory scan of eight shader packs used during development (comment-stripped source of every `.vsh/.fsh/.glsl/.csh` in each ZIP, includes
and libraries counted once each). Pack archives are not included. Numbers are how many times the construct occurs in that pack; blank means it never does.
The runtime rows summarize the specific profiles and scenes recorded, not a reproducible full-pack certification. The development performance capture
is not pinned to a source commit; see the [renderer demo](renderer-demo.md) for its conditions and limits. The clean-checkout `architectureTest` target
checks selected planner and translation rules without those packs.
Path for every shader: legacy/modern GLSL -> `#version 330 core` (`LegacyGlsl`, `IrisTerrain`, `IrisWorldAdapter`) -> glslang -> SPIR-V ->
SPIRV-Cross -> Metal Shading Language (MSL). Rows are sorted Done, then Partial, then No; columns by how far each pack gets.

## How far each pack gets (most complete first)

| # | Pack | Plans (Overworld / Nether / End) | All programs compile (glslang) | Runs in the game | Blocked by |
|---|---|---|---|---|---|
| 1 | Complementary r5.5.1 | yes / yes / yes | yes (22 of 22) | **Yes** (POTATO and default options; one scene checked) | LOW+ profiles need `shadowcolor1`; compute path off on macOS by design |
| 2 | BSL v10.1.8 | yes / yes / yes | yes (28 of 28) | **Mostly** (first test run fine; a later run was switched off on `timeAngle`) | `timeAngle` uniform unknown; `renderStage` always 0; entity ids 0 |
| 3 | Complementary r5.9.3 | Nether: yes with Medium profile; other dimensions not verified | not fully audited (27 programs reported in the run) | **Yes** (Medium, Nether, one 40-second run; see [renderer demo](renderer-demo.md)) | other profiles and dimensions are unverified; the full pack scan finds conditional features this build does not support |
| 4 | CH_MotionV4 | yes / yes / yes | yes (16 of 16) | No: falls back (Metal compile error "Native pipeline unavailable") | MSL error in generated vertex/fragment pair, not yet diagnosed |
| 5 | miniature-shader 2.19 | yes / yes / yes | **No** (`highp` precision qualifier mismatch) | No: falls back | precision qualifiers on overloaded functions |
| 6 | Simply_Upscaled 0.4.5 | yes / yes / yes | **No** (`#if` with tokens glslang rejects) | No: falls back | a preprocessor expression form |
| 7 | Mellow 3.4.1a | no (`vec2` custom uniform `taaJitter`) | not reached | No: refused | vec2 custom uniforms, compute, images, `#version 4xx` |
| 8 | Just Colored Lighting 1.0.2 | no (reads `Macro_texture`) | not reached | No: refused | custom images / compute / colortex8+ |

## Translation table

| OpenGL / GLSL call | C 5.5.1 | BSL | CH_Mo | mini | Simply | C 5.9.3 | Mellow | JCL | Translated to (GLSL 330 core -> SPIR-V -> Metal MSL) | Status | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `varying` | 1 | 236 |  | 90 | 167 |  |  | 132 | `out` (vertex) / `in` (fragment) -> MSL `[[user(locnN)]]` struct members | Done |  |
| `attribute` | 16 | 33 |  | 4 | 8 | 16 | 42 | 7 | `in` -> MSL `[[attribute(n)]]` vertex-descriptor slots | Done |  |
| `gl_Vertex` | 16 | 31 | 8 | 3 | 10 | 17 | 16 | 15 | `vaPosition + chunkOffset` (terrain/entities); screen-quad from `gl_VertexIndex` (full-screen) | Done | terrain position is unpacked from Sodium's 10-bit format |
| `gl_Normal` | 16 | 19 |  | 11 | 3 | 19 | 14 | 4 | `vaNormal` | Done |  |
| `gl_Color` | 18 | 22 | 7 | 9 | 50 | 19 | 22 | 46 | `vaColor` | Done |  |
| `gl_MultiTexCoord0/1` | 25 | 45 | 8 | 11 | 41 | 27 | 68 | 35 | `vaUV0` / `vaUV2` (light), overlay `vaUV1` | Done |  |
| `gl_ModelViewMatrix`, `gl_ProjectionMatrix`, `gl_ModelViewProjectionMatrix` (+inverses) | 21 | 50 | 8 | 1 | 19 | 23 | 18 | 26 | `modelViewMatrix`, `projectionMatrix` (std140 block / push constants -> MSL `constant` buffer) | Done |  |
| `gl_NormalMatrix` | 20 | 26 |  | 3 | 4 | 23 | 27 | 5 | `normalMatrix` | Done |  |
| `gl_TextureMatrix[0..1]` | 29 | 37 | 8 | 10 | 42 | 31 | 46 | 38 | `textureMatrix` (atlas scroll); [2+] unsupported | Done |  |
| `ftransform()` | 23 | 28 |  | 9 | 29 | 23 | 33 | 25 | `projectionMatrix * modelViewMatrix * vec4(position,1)` | Done |  |
| `gl_FragData[n]` / `/* RENDERTARGETS */` | 50 | 117 | 1 | 17 | 36 | 72 |  | 24 | `layout(location=n) out outColorN` -> MSL `[[color(n)]]`; colortex0-7 only | Done | more than 8 buffers refused |
| `gl_FragColor` |  | 1 |  |  |  |  |  |  | `outColor0` | Done |  |
| `gl_Fog.start/end/scale` | 3 |  |  | 3 |  | 3 |  |  | `fogStart`, `fogEnd` uniforms | Done |  |
| `texture2D()`, `texture3D()` | 144 | 180 |  | 17 | 89 | 118 |  | 61 | `texture()` -> MSL `tex.sample(smp, uv)` | Done |  |
| `texture2DLod()` | 24 | 47 |  |  | 8 | 75 |  | 21 | `textureLod()` -> MSL `level(l)` | Done |  |
| `texture2DGrad(ARB)()` |  | 17 |  |  | 16 |  |  | 24 | `textureGrad()` -> MSL `gradient2d` | Done | added today |
| `gl_VertexID` | 2 | 1 |  | 1 |  | 3 | 2 | 1 | `gl_VertexIndex` -> MSL `[[vertex_id]]` | Done | added today (compile failed before) |
| `gl_FragCoord` | 57 | 75 |  | 1 | 42 | 74 | 69 | 69 | same -> MSL `[[position]]` (y origin: top-left in Metal, handled by the pass flip) | Done |  |
| `gl_FrontFacing` | 1 |  |  |  |  | 1 |  |  | same -> MSL `[[front_facing]]` | Done |  |
| `dFdx`, `dFdy` | 22 | 22 |  |  | 2 | 22 | 36 | 4 | same -> MSL `dfdx` / `dfdy` | Done |  |
| `texelFetch`, `textureSize` | 87 | 15 |  |  | 10 | 111 | 37 | 19 | same -> MSL `tex.read(uint2, lod)`, `get_width/height` | Done |  |
| `textureLod`, `textureGrad` | 4 |  |  |  | 31 | 23 | 59 | 51 | same | Done |  |
| integer / bit ops (`uint`, `floatBitsToInt`) | 112 | 10 |  |  |  | 123 | 22 | 11 | same -> MSL integer ops | Done |  |
| `vec2[8](...)` initialisers | 12 | 18 |  | 1 |  | 15 | 15 |  | same -> MSL constant arrays | Done |  |
| `layout(location=n)` |  | 6 | 7 |  |  | 3 | 35 | 6 | same | Done |  |
| `#version 120` |  | 179 |  | 66 | 147 |  |  | 90 | rewritten to `#version 330 core` | Done |  |
| `#version 130` | 174 | 3 |  |  |  | 213 |  |  | rewritten to `#version 330 core` | Done |  |
| `#version 330` |  |  | 16 |  |  |  |  |  | kept (`core`) | Done |  |
| `mc_Entity` (block id) | 7 | 17 |  | 15 | 13 | 8 | 28 | 39 | palette index in vertex data -> `MxBlockIds[]` table from `block.properties` | Done | entity/block-entity ids are always 0 |
| `uniform sampler2D texture/tex` | 1 | 17 |  |  | 28 | 1 |  | 17 | renamed `gtexture` -> `u_BlockTex` (Sodium atlas) -> MSL `texture2d`+`sampler` | Done | `texture` rename added today |
| `lightmap` | 11 | 183 | 4 | 5 | 23 | 23 |  | 37 | `u_LightTex` / `Sampler2`; bound only if the pipeline has it | Done | fixed today (eyes) |
| `noisetex`, `texture.*` (PNG) | 48 | 63 |  |  | 2 | 46 | 159 | 3 | PNG loaded to `DynamicTexture`, bound by slot (48 slots) | Done |  |
| `shadowtex0/1`, `shadowcolor0` | 14 | 18 |  | 2 | 32 | 14 | 86 | 50 | depth/colour attachment of the shadow pass | Done | shadow map renders; not compared with Iris |
| `colortex0-7`, `gcolor`, `gnormal`... | 104 | 115 | 3 | 7 | 18 | 111 | 257 | 49 | `RGBA8/16F...` textures; ping-pong per `flip.*` | Done | 3-channel/packed formats widened to 4 channels |
| `depthtex0/1/2` | 38 | 49 | 2 | 4 | 8 | 41 | 64 | 21 | copies of depth converted to OpenGL range | Done |  |
| `#version 4xx` (compat / compute) | 3 | 3 |  |  |  | 6 | 284 | 137 | only the compute/image parts are blocked, see below | Partial |  |
| `mc_midTexCoord` | 17 | 22 |  |  | 2 | 25 | 51 | 5 | derived from the quad's UV centre (approximation) | Partial | hair-away approximation, not exact |
| `at_tangent` | 21 | 29 |  |  | 3 | 21 | 39 | 3 | 16-direction quantised tangent decoded in the vertex shader | Partial | quantised; non-terrain: any perpendicular |
| `at_midBlock` | 3 | 2 |  | 3 |  | 4 | 4 | 12 | computed for terrain; 0 for entities/others | Partial |  |
| `normals`, `specular` (PBR atlas) | 2 | 14 |  |  | 2 | 2 | 24 | 6 | built-in flat 1x1 textures (no PBR data) | Partial | labPBR atlas not supplied |
| `sampler2DShadow`, `shadow2D()` | 2 | 5 |  |  |  | 2 | 25 |  | plain `sampler2D` + manual 2x2 depth compare (`mx_shadowCompare`) — Metal sampler here does not compare | Partial | emulated bilinear PCF, looks the same, not bit-exact |
| `#extension GL_...` | 1 | 58 |  |  |  | 6 | 68 |  | ignored / kept; `GL_ARB_shader_image_load_store`, `GL_ARB_compute` not honoured | Partial | harmless unless images/compute used |
| `colortex8-15` | 2 | 21 |  |  |  | 6 |  | 8 | only 0-7 exist | No | planning refuses it |
| `shadowcolor1` (2nd shadow attachment) | 3 |  |  |  |  | 3 |  |  | no second shadow colour attachment | No | blocks Complementary LOW+, 5.9.3, End |
| `image2D`, `imageLoad/imageStore` | 14 | 8 |  |  |  | 16 | 140 | 95 | not translated | No | needs custom images / compute |
| `atomicAdd/Or/Max/Min` |  |  |  |  |  | 6 |  |  | not translated | No |  |
| `layout(std430) buffer` |  |  |  |  |  | 2 |  |  | not translated | No |  |
| compute shaders (`.csh`, `dispatch`) | 3 | 3 |  |  |  | 3 | 5 | 21 | not run (programs listed as 'compute only') | No | Complementary disables its compute path on macOS (`MC_OS_MAC`) |

## Not tied to one GLSL construct

| OpenGL / Iris feature | Translated / handled as | Status |
|---|---|---|
| `glDrawBuffers` (`RENDERTARGETS`) | multi-target Metal render pass, colortex0-7 | Done |
| ping-pong `flip.*`, `clear.*` | per-pass buffer swap / clear | Done |
| `glCopyTexSubImage` of depth | depth copy pass converting to OpenGL range (depthtex0/1/2) | Done |
| `shaders.properties` `#if/#define`, profiles, `screen=` | preprocessed; profiles work; `screen=` layout not (flat option list) | Partial |
| `block.properties` ids | palette + `MxBlockIds` table | Done |
| `entity.properties`, `item.properties`, `dimension.properties` | not read; `entityId`, `heldItemId`, `entityColor` = 0 | No |
| `blend.*`, `alphaTest.*` directives | not applied | No |
| `renderStage` | uniform exists, always 0 (sun/moon/stars indistinguishable) | Partial |
| Custom uniforms (`uniform.float.*`, `variable.*`) | evaluated on the CPU, `vec2/3/4` not yet | Partial |
| Compute shaders, images, SSBOs, geometry shaders | not translated | No |
| `gl_Fog`, `fogStart/fogEnd` | uniforms from the game's fog | Done |
