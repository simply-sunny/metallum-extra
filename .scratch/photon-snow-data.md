# Photon snow-yellow — coagulated data (helper agent, 2026-10-15)

## Test results (just ran, all green)
- `packTest`: 343 checks, 0 failed.
- `architectureTest`: 12/12 PASS (composite ordering, buffer flips, GLSL rewrites, cache invalidation).
- `packCheck photon_v1.3b.zip`: 525 files, 497 options; PLAN OK on all 3 dimensions
  (world0: 44 steps, world-1: 41, world1: 42; buffers 0–14).
  Not-run-yet everywhere: gbuffers_damagedblock, gbuffers_line, shadow_water (expected — no game).
- Conclusion: pack loads, plans, and translates. Yellow snow is NOT a load/plan failure.
  It is a runtime value problem (light/sh color or material classification).

## Photon snow evidence (read-only zip analysis)
- `block.properties:41`: `#define tags_snow %snow` declared but never assigned to any `block.100xx`.
- Only snow mapping: `block.properties:318` → `block.10080 = snow ...` (snow layer → mask 80).
- `snow_block`, `powder_snow` unmapped → fall back to mask 0 (Default).
- `utility.glsl get_material_mask()`: `mc_Entity.x - 10000` for terrain; mask 80 = Transparent, mask 0 = Default.
- `material.glsl`: default `sss_amount = 0`; plants/leaves 0.5–1.0, ice 0.75, wool (mask 15) 0.1.
  Mask 0 and 80 get NO sss, roughness 1.0.
- Snow albedo ≈ white, so `final = albedo * light * rcp_pi` amplifies any warm tint in light.
- Sky-map writers: `d0_sky_map.fsh` → colortex4 column `sky_map_res.x` (y0 = light_color, y1 = ambient_color);
  `d4a_generate_sky_sh.csh` → `imageStore(colorimg4, ivec2(191, 2+band))` ×9 + `ivec2(191,11)` = irradiance_up.
- Readers: `d4_deferred_shading.fsh` (flat light_color/ambient_color + diffuse + spec),
  `c1_blend_layers.fsh` (sky-map sample + fog).
- Noon defaults: `SUN_NR=NG=NB=1.0`, `SUN_I=1.0` — user tint innocent at noon; suspect
  `atmosphere_transmittance * sunlight_color * exposure` or SH-baked horizon warmth.

## Hypotheses (ranked)
1. Warm SH skylight / `irradiance_up` (191,11): horizon warmth baked into sky map at noon.
   Prove: log texel (191,11) + (sky_map_res.x, 1); guilty if B < R by >5% at noon clear.
2. Warm `light_color` from atmosphere transmittance path (not user tint).
   Prove: log `light_color`, `sunlight_color`, `sun_dir.y`, `time_noon/sunrise/sunset`;
   guilty if `light_color.b / light_color.r < 0.95`.
3. Snow misclassification (mask 0/80 default path; `snow_block` maybe aliasing a small vanilla ID
   → spurious SSS/sheen `+2.0*ambient_sss*sss` boost). Prove: log per-pixel
   `material_mask`, `sss_amount`, `albedo`, `light_levels.y`, `NoL`.

## Reference run spec (Iris OpenGL, still to do)
- Pair: Iris 1.11.7+26.3-fabric + Sodium mc26.3-0.9.2-fabric (declared-good combo).
- Scene: time 6000 noon, weather clear, exposure auto OFF; plains control (grass + white wool)
  vs snowy slopes (snow layer mask 80 + snow_block unmapped); look straight down at sunlit flat snow.
- Log per frame: `light_color, ambient_color, sunlight_color, sun_dir/sunAngle,
  time_noon/sunrise/sunset, sky_sh[9], irradiance_up, SSS_INTENSITY, SUN_NR/NG/NB, SKYLIGHT_I`
  + texels `(sky_map_res.x,0/1)`, `(191,2..10)`, `(191,11)` + snow-vs-wool pixel RGB.
