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

## Progress (2026-10-16)
- Player body: fixed earlier (translucent entities drawn before deferred).
- First-person hand: the solid part of the held item and the arm are now drawn before the deferred
  programs with gbuffers_hand (on by default; `-Dmetallumextra.earlyHand=false` turns it off). Translucent
  held items (stained glass) and the glint still draw in the game's own turn, with gbuffers_hand_water.
  The arm uses the translucent entity pipeline but counts as solid, as in Iris.
- Verified under Photon: the sword, arm, and held stone show up; the red stained glass in the off hand draws translucent.
- Still open, not caused by the hand path: under Photon the held sword's face is near-black whenever it
  faces away from the sun, because `sky_sh` irradiance is ~0 for every pixel (the shaded sides of world
  blocks go black too). That is the colortex4 sky-SH issue in ledger.md, not this one.
- BSL re-checked with the restored pack: the sword, the arm, and the off-hand stained glass all render.
