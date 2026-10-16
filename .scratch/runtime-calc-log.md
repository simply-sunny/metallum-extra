# Runtime-calculation log — Photon yellow snow (helper agent, 2026-10-15)

Headless execution of the REAL runtime ops (`/tmp/snowlog/SnowCalcLog.java`, compiled
against `build/classes/java/main`, read-only). Part 1 calls `IrisUniformValues.frame()`
directly; Part 2 uses real `celestialDirection()`/`irisSunAngle()` + byte-identical
`Shaders.java:302-326` float math; Part 3 evaluates `setLinear` numerically.

## Headless output (verbatim)

```
== PART 1: real IrisUniformValues.frame() @ canonical noon ==
  sunAngle =   0.2500
  shadowAngle =   0.2500
  timeAngle =   0.2500
  worldTime = 6000.0000
  sunPosition =   0.0000 100.0000   0.0000
  moonPosition =   0.0000 -100.0000   0.0000
  shadowLightPosition =   0.0000 100.0000   0.0000
  upPosition =   0.0000 100.0000   0.0000
  fogColor =   0.7500   0.8300   1.0000     (ASSUMED game input, passed through)
  skyColor =   0.4700   0.6500   1.0000     (ASSUMED game input, passed through)
  eyeBrightness =   0.0000 240.0000
  eyeBrightnessSmooth =   0.0000 240.0000
  rainStrength =   0.0000
  wetness =   0.0000
== PART 2: day sweep (gameDeg = game sun angle; noon=0, sunset=90, midnight=180) ==
  gameDeg irisSun sunY   sunUp  day    high  strength  lightR lightG lightB  R/B   G/B
    0     0.2500  1.0000 1.0000 1.0000 1.0000 3.3000  3.3000 3.0690 2.6400 1.25  1.16
   75     0.4583  0.2588 1.0000 1.0000 0.7142 2.8999  2.8999 2.3903 1.9055 1.52  1.25
   90     0.5000 -0.0000 0.2593 0.2473 0.0000 0.4926  0.4926 0.2759 0.1478 3.33  1.87
  105+    night branch (moon light 0.46/0.62/1.0 * strength)
  270     0.0000  0.0000 0.2593 0.2473 0.0000 0.4926  0.4926 0.2759 0.1478 3.33  1.87
  285     0.0417  0.2588 1.0000 1.0000 0.7142 2.8999  2.8999 2.3903 1.9055 1.52  1.25
  300+    noon branch again, R/B back to 1.25
== PART 3: setLinear space check (ShaderGlobals.java:49-53, pow(c,2.2)) ==
  in (0.47,0.65,1.00) -> setLinear (0.190,0.388,1.000)   R/B 0.47 -> 0.19
  in (0.75,0.83,1.00) -> setLinear (0.531,0.664,1.000)   R/B 0.75 -> 0.53
  (fogColor/skyColor uniforms pass the SAME game values RAW — IrisUniformValues:173-174)
```

## Op-by-op verdicts (what matches native OpenGL/Iris, what doesn't matter, what's open)

| # | Operation | File:line | Verdict |
|---|-----------|-----------|---------|
| 1 | `irisSunAngle`: game 0 → Iris 0.25; sweep hits 0.25 noon / 0.5 sunset / 0.75 midnight / 0.0 sunrise | IrisUniformValues:67-71 | SANE, matches Iris convention (0.25=noon). |
| 2 | `celestialDirection` sun path: sun.y 1→0→-1→0 across noon→sunset→midnight→sunrise | IrisUniformValues:74-76, Shaders:302-303 | SANE. Magnitudes cancel in Photon's `view_sun_dir_norm` expressions anyway. Rotation ORDER vs native Iris still open (Iris-source fetch failed, network) — verify `sun_dir.y` at sunrise/sunset on the reference run. |
| 3 | `fogColor`/`skyColor` raw passthrough | IrisUniformValues:173-174 | MATCHES native Iris convention (raw game values). Photon `d0_sky_map` reads `fogColor` raw on both. Not the bug by itself. |
| 4 | Warm noon sun `lightColor` R/B=1.25, `setLinear` pow-2.2 crushing R/B 0.47→0.19 | Shaders:319, ShaderGlobals:49-53 | **EXONERATED for Photon**: feeds only `MxLightColor`/builtin path (IrisPipeline:989); Photon never reads `Mx*` names. Only matters for the BUILTB-IN pack control shot. Do NOT "fix" these to chase Photon snow. |
| 5 | Photon `sun_dir`/`eye_skylight` custom-uniform chains | Photon shaders.properties + CustomUniforms | Pure algebra over ops 1–3 (all sane) → direction correct at noon by construction. OPEN only for float-precision/translation issues in CustomUniforms eval. |
| 6 | Sky-map texels (191,0/1/11) + center pixel, live | IrisPipeline:537 `logPhoton()` (other agent's WIP, already wired: auto-fires on trace w/ Photon, `-Dmetallumextra.logPhoton`, DebugScript `logPhoton` step) | THIS is the live half. Run it; compare against the headless expectations above. |
| 7 | colortex4 storage: Photon declares NO `colortex4Format` → RGBA8_UNORM on both Metallum (IrisPlan:907 default) and native Iris | Photon shaders.properties, IrisPlan:907 | Format MATCHES. Sky values >1 clip identically on both — not a divergence (but note: R/B distortion from clipping is then a Photon-on-Iris trait too; reference run will confirm). |

## Pinpoint: the operation to match to native OpenGL

The celestial/uniform math (ops 1–3) is sane and conventional — stop looking there.
The warm-sun/setLinear path (op 4) is builtin-only — stop looking there for Photon.
Match these, in order:
1. **CustomUniforms evaluation of Photon's `sun_dir`/`moon_dir` chains** against CPU-evaluated
   reference values (log Part 1 gives the inputs: `sunPosition=(0,100,0)` + identity
   modelViewInverse ⇒ `sun_dir` must be exactly `(0,1,0)` at noon; any deviation is the bug).
2. **GLSL→MSL translation of the sky/SH math** (`d0_sky_map.fsh`, `d4a_generate_sky_sh.csh`,
   `diffuse_lighting.glsl` SSS branch) — compare `logPhoton()` texel output vs the Iris-OpenGL
   reference run logging the same texels.
3. **3D LUT (`depthtex0`) sampling + buffer clear values** if 1–2 match but snow is still yellow.
