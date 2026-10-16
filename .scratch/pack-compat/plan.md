# Pack compatibility plan (from user playtest 2026-10-15, ~40 screenshots in `clusters/26.3/screenshots/`)

Source: user's narrated run-through of 20+ packs. Screenshots are evidence; pack zips in
`metallum-extra/run/shaderpacks/`. Headless compile matrix running (background) → results
will land in `/tmp/snowlog/compile-matrix.log`, then coagulated here.

## Cross-cutting (fix once, helps every pack)

| ID | Symptom | Suspect area | Status |
|----|---------|--------------|--------|
| X1 | Entity hurt red tint missing on ALL Iris packs (works in Metallum internal shaders → pipeline exists, Iris programs never reach it) | Overlay path: `IrisWorldAdapter` `mx_overlay_color` returns `vec4(1.0)` when `!format.overlay()` (line 344-345); entity pipeline may never supply overlay coords | `ready-for-agent` |
| X2 | Non-full-block foliage gone (grass, drip leaves, glow berries) on b&w / BakedPotato / Mellow; grass visible THROUGH walls on Mellow/BakedPotato | Cutout layer (`gbuffers_textured`) on Sodium path; depth/cull on substituted cutout pipelines | `ready-for-agent` |
| X3 | Water missing entirely (b&w) / no water fog (BSL, Miniature, Better Default, Photon-edge) while in-water fog works | Water translucent layer + fog uniforms (`isEyeInWater` transition, fog color/density) | `ready-for-agent` |
| X4 | See-through-world / depth broken (BakedPotato staring at deepslate) | Depth attachment on substituted pipelines (echo of 14:10 attachment-size crash) | `ready-for-agent` |
| X5 | Complementary: colors "messed up" + reflection smear when strafing at low FPS | Color-space mixing (raw vs `setLinear`, see `runtime-calc-log.md` op 4 is builtin-only — recheck for custom uniforms) + history/previous-matrix temporal reprojection | `needs-info` (need same scene on Iris-OpenGL reference) |

## Per-pack

| Pack | Verdict | Issues | Status |
|------|---------|--------|--------|
| Complementary Unbound + Reimagined | Mostly solid | X1 (hurt), X5 (colors, reflection smear @ low FPS strafe) | `ready-for-agent` |
| b&w shader | Broken | X2 (all foliage gone), X3 (no water at all), glass shows highlight only, lava OK, end entities unrendered (OK?) | `ready-for-agent` |
| BakedPotatoFinal | Broken | X4 (see-through world), X2 (non-full-blocks) | `ready-for-agent` |
| Bliss | Loads nothing | Uses >8 colortex; needs >8 buffer emulation (already pinboarded) | `ready-for-human` (design decision) |
| Bloop | Doesn't load | Exact error mined (see plan): `GlslLocations$Member` class-file load hits `ZipException bad LOC` — pack zip verified HEALTHY, voxy files are the pack-side trigger; plans+compiles clean headless so it's load/runtime-only | `ready-for-agent` |
| BSL | Good | X1 only (+water see-through/no fog = X3, missing ice reflections on High, haze probably shader-side) | `ready-for-agent` |
| iterationT 3.2.0 | Unplayable | All-purple lighting getting pinker with distance, white sky, yellow sky flashes, heavy fog ("Mist Central"), hand flashes, purple ores; first reload crashes then falls back to previous pack | `ready-for-agent` (likely uniform-block layout or garbage light/fog values — broad blast radius, high value) |
| MakeUp UltraFast | Black screen + flashes | Final composite missing | `ready-for-agent` |
| Mellow | Loads, broken | X2 grass-through-walls, weird water reflections | `ready-for-agent` |
| Miniature | Near-complete ✅ | Ice reflection points UP when viewed from in water; verify vs Iris-OpenGL whether shader-side or ours | `needs-info` |
| Noble | Graceful refuse ✅ | Storage buffers unsupported (caught, no crash) — long-term feature | `wontfix` for now |
| Photon | Good-ish | Sky bug (3 shots 16:22ish), player body see-through in 1st/3rd person (cape renders — entity alpha path!), held items not rendered, water-edge floor see-through, rail edges, dark spots. NEW: Nether is a total red washout (shot 16:36:24, F3: 26 passes/5 copies, 41 programs, shadows 2048px) vs OFF shot 16:36:37 which is dark but readable — fog/ambient path for world-1 suspect (uniform density or `minAmbient` 2.4-weight branch) | `ready-for-agent` |
| RenderPearl v2.8 beta | Near-vanilla? | Verify intended look vs actual | `needs-info` |
| TAA Project | Works ✅ | — | closed |
| Better Default | Works, artifacts | 5x stacked reflections on ice, overbright particles, edge problems, water particle fragmenting | `ready-for-agent` |
| Velocity (Mellow fork) | Works, artifacts | Angle-dependent striping, ice shading flips with view angle, 1-off lag spike | `ready-for-agent` |
| E-lite | Black (caught, no crash) ✅ | Worst-pack stress case; revisit later | `wontfix` for now |
| Metallically Beautiful | Nether @200fps, no spikes ✅ | — | closed |
| I Like Vanilla | Format gap | Composite declares RGBA4 → need format support (cf. IrisPlan format table) | `ready-for-agent` |
| Sildur's Vibrant / Basic, RedHat, OPAL, Energy, Night Vision, Just Colored Lighting, Smoother Glowing | Not covered in this run | Await reports or matrix results | backlog |

## Headless matrix results (2026-10-15, `compileCheckAll` over all 27 zips — all green)

Every pack that plans COMPILES with 0 failed stages. All in-game visual bugs are
runtime, not compile. Full log: `/tmp/snowlog/compile-matrix.log`.

- BSL 32/0, BakedPotato 25/0, Bloop 33/0, Complementary R/U 32/0, MakeUp 29/0, Mellow 32/0,
  Noble 56/0 (!), OPAL 31/0, RedHat 27/0, Sildur's Basic+Vibrant 21/0, TAA 21/0,
  Better Default 16/0, Track1Test 7/0, Velocity 33/0, b&w 1/0, iterationT 45/0,
  Miniature 20/0, photon 44/0, E-lite 28/0, Just Colored Lighting 19/0.
- Bliss: REFUSED at plan — 9 buffers (colortex0,1,2,7,8,9,11,14,15), Metal max 8, can't
  pack down. Confirms user report; >8 emulation is the fix (pinboard).
- I Like Vanilla: REFUSED — `composite1 gives colortex8 the format RGBA4, which this
  version does not support`. Concrete feature ask: RGBA4 support.
- Energy / Night Vision / Smoother Glowing: zips contain no `shaders/` folder (nested
  layout?) — loader could look one level deeper. Not user-reported; backlog.
- RenderPearl v2.8.0-beta.4: 0 programs ("None of its programs can run in this version
  (it has none)") — it's a library/render-engine mod, not a shader pack. Wontfix, stop
  testing it as a pack.

## In-game log errors mined (sessions 15:48, 15:58, 16:10+ in `clusters/26.3/logs/`)

- Bloop: `broken and switched off: Failed to load class file for
  'com.metallumextra.shader.GlslLocations$Member'! - Z` ← `ZipException: invalid LOC
  header (bad signature)`. Bloop's zip is HEALTHY (441 entries, `unzip -t` clean), so the
  bad zip is whatever was being opened for class bytes at that moment, not the pack.
  Bloop-specific triggers in-zip: `voxy.json` / `voxy_opaque|translucent.glsl`
  (custom chunk format!) + `shadowcomp.csh`. Notably Bloop plans (32 steps) and compiles
  (33 programs) clean headless — failure is load/runtime-only.
- Just Colored Lighting (bonus, not user-reported): `images of the pack's own are not
  supported yet (cimage1)` — clean unsupported-feature message, wishlist item.
- Noble: `storage buffers are not supported yet (illuminanceBuffer)` — exact name logged.
- 2× `Unable to find shader defined uniform (colortex8)` right around the Bloop→BSL
  switch (16:08) — unattributed; check which pack declares colortex8 without defining it.
- iterationT 16:10:20 → the blend-functions crash (already fixed per ledger).

## Proposed split

- **pi (src/main owner):** X1 (hurt overlay), X2 (cutout/Sodium), X3 (water/fog), X4 (depth),
  iterationT-purple, MakeUp-black, Photon player-body, Better-Default stacking. Pick in that order.
- **Helper (me):** headless compile matrix for all packs (running), Iris-OpenGL reference
  comparisons for X5/Miniature-ice/Complementary-colors, screenshot evidence mapping,
  triage upkeep. No `src/main` edits.
- **User:** for each new report, pack name + 1 on/off screenshot pair + `/logphoton`-style
  texel dump where relevant.

## Issues (P0/P1 as files)
- `issues/01-entity-hurt-overlay.md` (X1)
- `issues/02-cutout-foliage-missing.md` (X2)
- `issues/03-water-missing-no-fog.md` (X3)
- `issues/04-see-through-world-depth.md` (X4)
- `issues/05-iterationt-purple.md` (iterationT)
- `issues/06-temporal-reflection-smear.md` (X5)
- `issues/07-photon-player-body-seethrough.md` (Photon entity alpha)
