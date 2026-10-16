# 02 — Cutout foliage missing (grass, leaves, glow berries) / grass through walls

Status: ready-for-agent

## Symptom
b&w, BakedPotato: short/tall grass, drip leaves, glow berries — anything not a full
block — gone. Mellow/BakedPotato: grass visible THROUGH walls (depth/cull wrong on the
same layer, inverse symptom).

## Suspect
Cutout layer (`gbuffers_textured`) on the Sodium path: pipeline substitution, atlas
binding (`Format.of`), alpha-test threshold (`remapOutputs` 0.1), depth-write/cull state
on the substituted cutout pipeline. Through-walls = depth test off or wrong depth target.

## Done when
b&w renders grass/leaves; Mellow shows no grass through walls.
