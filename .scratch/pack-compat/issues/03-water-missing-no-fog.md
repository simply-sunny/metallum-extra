# 03 — Water missing / no water fog (surface), in-water fog works

Status: ready-for-agent

## Symptom
b&w: no water at all. BSL/Miniature/Better-Default: water fully see-through, no fog;
ENTERING water gives fog correctly. Photon: at the water edge you see through the floor.

## Suspect
Water translucent layer + fog uniforms. In-water fog works ⇒ the fog machinery is fine;
the surface/edge transition (`isEyeInWater`, `eye_skylight`, fog start/end above surface)
is not. Photon edge case smells like missing water-surface plane at the boundary.

## Done when
BSL water has fog from above; b&w renders water; Photon edge keeps the floor opaque.
