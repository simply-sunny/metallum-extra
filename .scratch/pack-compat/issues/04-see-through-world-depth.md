# 04 — See-through world / depth broken (BakedPotato)

Status: ready-for-agent

## Symptom
BakedPotato: staring at deepslate that should be hidden — full x-ray. Screenshot
`2026-10-15_15.51.52.png` vicinity (note: that shot may be mid-rebuild churn; repro
stable first: enable pack, wait 3s+, screenshot).

## Suspect
Depth attachment on substituted pipelines (echo of the 14:10 attachment-size crash).
Check depth target size/format vs color targets on the terrain pipelines for this pack
(4 color, 2 depth per its log line).

## Done when
BakedPotato renders opaque occlusion correctly; no x-ray stable repro on any pack.
