# Parallel-agents ledger

Shared coordination file. Append your entry at the bottom; keep entries short.
Rule: never edit another agent's entry. Read-only on `src/main/**` unless your entry claims it.

## Agent A — helper (OpenCode, mc-rec-util session)

- **Doing:** reference test instance for Photon snow-yellow bug: stock Iris (OpenGL) + Sodium on MC 26.3,
  isolated from this repo, to compare against Metallum Metal output.
- **Owns/writes:** new dir `photon-snow-ref/` (outside this repo) + `run/iris-opengl-ref/` scratch only.
  Will NOT touch: `src/main/**`, `src/test/**`, `logs/`, `build/`, `run/saves/`, `run/shaderpacks/`.
- **Reads (read-only):** `run/shaderpacks/photon_v1.3b.zip`, docs.
- **Status:** research done (see notes below), instance setup next.
- **Findings for whoever debugs the tint:**
  - Iris 1.11.7+26.3-fabric + Sodium mc26.3-0.9.2-fabric is the declared-good pair for the reference run.
  - Photon maps only `block.10080 = snow...` (mask 80); `snow_block`/`powder_snow` unmapped → mask 0 default, no SSS.
  - Top suspects for yellow snow at noon: (1) warm SH `irradiance_up` texel (191,11);
    (2) warm `light_color` from atmosphere transmittance; (3) snow misclassification (mask 0/80).
    Log these texels/uniforms on the reference run to compare.

## Agent B — pi session

- **Doing:** In-repo diagnostic instrumentation and root-cause analysis for Photon's yellow/green snow tint on Metal (Metal pipeline side).
- **Owns/writes:** `src/main/**`, `src/test/**`, `build/**`, `docs/**`, `local-testing.gradle`.
- **Status:** Programmatic logging added to `IrisPipeline` (`logPhoton()`):
  - Dumps `colortex4` texels at `(191, 0)` (`light_color`), `(191, 1)` (`ambient_color`), `(191, 2..10)` (`sky_sh`), `(191, 11)` (`skylight_up`), plus center-screen GBuffer albedo/mask (`colortex1`) and final shaded RGB (`colortex0`).
  - Flags tint ratio anomalies (`R/B` and `G/B` balance) on snow.
  - Added `logphoton` / `photon` verb to `DebugScript`.
  - Rebuilt jar and deployed to OneClient `clusters/26.3`.
- **Next:** Ready to compare texels `(191, 0)`, `(191, 1)`, and `(191, 11)` against Agent A's reference Iris-on-OpenGL dump to isolate whether the warm shift originates in `d0_sky_map` atmosphere transmittance or in `d4a_generate_sky_sh` reduction.

