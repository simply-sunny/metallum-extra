# 05 — iterationT: everything purple, white sky, flashes, heavy fog

Status: ready-for-agent

## Symptom
All light purple (ores purple, worse/pinker with distance), white sky, random yellow sky
flashes, "Mist Central" fog, hand flashes. First reload crashes, falls back to previous
pack. Motion blur works.

## Suspect
Garbage light/fog values: uniform-block layout mismatch (member offsets for this pack's
custom uniforms?), uninitialized history buffers, or NaN spreading with distance
(purple = R+B with dead G fits a zeroed/offset green channel somewhere). Distance
dependence ⇒ fog or temporal factor. Check `logPhoton`-style texel dump under iterationT
if it declares the same sky-map texels; else dump its light/fog uniforms headlessly.

## Done when
iterationT daylight looks sane (no purple cast, no flashes); reload doesn't crash.
