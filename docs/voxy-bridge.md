# Voxy on Metal: how the bridge works

Status: **work in progress.** The code draws Voxy's level-of-detail (LOD) terrain
through Metallum's Metal pipeline. The bring-up instrumentation in
`MetalVoxyRenderer` is still in place, marked "remove once LOD is seen", so
LOD on screen is not yet confirmed.

[Voxy](https://github.com/MCRcortex/voxy) is an LOD terrain mod for distant chunks.
It is written against raw OpenGL 4.6. Metallum has no OpenGL context, so Voxy
cannot run as-is. This branch keeps Voxy's CPU side and replaces its GL side.

---

## The idea in one picture

```
 Voxy (unchanged)                         Metallum Extra bridge (this branch)
 ─────────────────                        ──────────────────────────────────
 world ingest ───────────────┐
 model bakery ───────────────┤  stays      glTextureSubImage2D ──► ModelAtlasMirror (CPU atlas)
 section meshing (workers) ──┘             glClearTexImage     ──► no-op (GlTextureMixin)
                                           glGenTextures etc.  ──► VoxyGlRegistry (fake names)
 OpenGL capability probe ────────────────► VoxyShimmedGlCapabilities (CapabilitiesMixin)
                                                  │
 finished sections ──► MetalVoxyVertexCapture ──► MetalVoxyRenderer ──► renderpearl pass
                       (packed quads →            (per-section draws,     into the game's
                        vertex records)            arena buffer)           main target
```

Voxy's own renderer is never called. `MetalVoxyRenderer` replaces it.

---

## Parts

| Class | Role |
|---|---|
| `MetalVoxyRendererManager` | Owns the one renderer. Follows Voxy's own lifecycle: a world change swaps the level; `allChanged` tears down and rebuilds. |
| `MetalVoxyRenderer` | Each frame: decides which sections are visible, requests meshes for missing ones, uploads vertices to an arena buffer, and draws them in one pass with push constants. Sections are tracked as `ST_PENDING` and so on, with a child-existence mask so the LOD tree can fall back to a coarser parent. |
| `MetalVoxyVertexCapture` | Converts a finished Voxy section into vertex records. Voxy writes packed quads (8 bytes each), in eight directional buckets; bucket 0 is the translucent one. |
| `VoxyMetalCompat` | Runs before Voxy's first GL call, which would otherwise throw under Metal and take the game down. |
| `voxy/gl/VoxyGlRegistry` | Hands out fake GL names for buffers and textures, and frees them. |
| `voxy/gl/ModelAtlasMirror` | Keeps a CPU copy of Voxy's 768×512 model atlas. Voxy's upload calls are redirected into it. |
| `voxy/gl/VoxyShimmedGlCapabilities` | A permissive `GLCapabilities` so Voxy's probe passes. |
| `mixin/voxy/*` | The redirects: GL buffer, texture, fence, persistent-map and pixel-store calls; Voxy's init and renderer switches; atlas accessors. |

---

## Frame flow

1. **Ingest and mesh (Voxy, unchanged).** Worker threads turn chunks into sections.
2. **Capture.** A finished section becomes vertex records (`MetalVoxyVertexCapture`).
   The result goes into a `ConcurrentLinkedQueue`; the render thread drains it.
3. **Visibility.** The renderer walks the LOD tree from the camera. A missing node
   is requested (`enqueueTask`) and a coarser ancestor is drawn meanwhile
   (`fallbackRoot`).
4. **Upload.** Vertices go into a growable arena buffer. When it grows, the old
   contents are copied on the GPU, so the arena needs `COPY_SRC`.
5. **Draw.** One render pass into the game's main colour and depth targets, using
   the `voxy_terrain` pipeline with the `internal/voxy/terrain` shaders
   (`src/main/resources/assets/metallum-extra/shaders/internal/voxy/`).

---

## Gotchas found so far

- **Re-creating the renderer aborts the JVM** with "No context is current" if
  Voxy zeroes a reused atlas texture. `GlTextureMixin` turns that call into a no-op
  because the texture is fake and its pixels live in `ModelAtlasMirror`.
- **Voxy's natives are Linux and Windows only** for zstd and LMDB. Its section
  saving runs on Sodium's chunk threads, so a missing macOS dylib kills those
  threads and no vanilla chunks build. `build.gradle` puts the macOS arm64
  natives on the dev classpath only.
- **Distant Horizons** is removed from this branch's runtime dependencies
  (`build.gradle`, `gradle.properties`). The reason is not written down in the
  branch yet; record it here when known.

---

## Building

- The Voxy jar is not in git. Place `libs/voxy-0.2.20-beta-23b3486.jar` (the
  commit `voxy-upstream-jar` branch in the consolidated repo) under `libs/`.
  `gradle.properties` names it.
- It is compile-only and on the dev client's classpath. It is never bundled.
- The upstream source (`voxy-263` branch) is licensed "All rights reserved, do not
  redistribute". Do not publish it or push it to the fork.

---

## Open items

- Confirm LOD on screen, then remove the bring-up counters and dumps.
- Verify the mesh and draw path with the debug frame dump (`Voxy frame dump`,
  `Voxy vert dump` in the log).
- Decide whether the voxy branch can be rebased onto a clean `main` once
  `metal-perf-pass` lands.
