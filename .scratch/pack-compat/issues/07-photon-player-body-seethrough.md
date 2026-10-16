# 07 — Photon: player body see-through, held items missing

Status: ready-for-agent

## Symptom
3rd person: copper ore visible THROUGH the body (cape renders fine). 1st person: trees
visible through the hand/body. Held items (axolotl eggs, etc.) not rendered; glass
renders both placed and held. Glow berries render. Rails have edge issues.

## Suspect
Entity/hand alpha path: entity translucency handling drops opaque entity fragments
(cape uses a different program — glint/emissive? — which is why it survives). Check the
entity/hand pipeline substitution: alpha-test threshold killing opaque pixels, or the
entity program's outputs misassigned (writes going to a non-displayed buffer while
colortex0 keeps cleared/transparent values). Glass surviving suggests the translucent
entity step works but the opaque entity step doesn't.

## Done when
Player body opaque in 1st/3rd person under Photon; held items render.
