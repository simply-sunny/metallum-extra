# 06 — Temporal reflection smear at low FPS + Complementary colors off

Status: needs-info

## Symptom
Complementary: reflections smear when strafing at low FPS; colors "sort of messed up".

## Suspect
History-buffer / previous-frame matrices (`gbufferPreviousModelView/Projection`,
`frameCounter`, TAA jitter) wrong at low FPS (smoothing in `IrisUniformValues.frame`
uses frameTime — verify half-life math under large dt). Colors: needs Iris-OpenGL
reference screenshot of the same scene to separate shader-look from our bug.

## Needs from helper
Reference-run comparison (Iris 1.11.7 + Sodium 0.9.2, same seed/scene/time).

## Done when
No smear strafing at ~15fps; colors match reference within tolerance.
