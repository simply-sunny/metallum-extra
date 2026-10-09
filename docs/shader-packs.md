# Making shader packs

A shader pack is a ZIP file of GLSL that replaces the look of Metallum Extra's shader pipeline. This page explains what a
pack is, how it is loaded, what your shaders can read, and how to work on one. For installing and choosing packs, see
"Shader packs" in [details.md](details.md).

Shader packs written for Iris or OptiFine do **not** work here. These packs are for Metallum Extra's own pipeline, which
runs on Metal (macOS, Apple Silicon) and needs Sodium.

## The short version

1. Take the built-in shaders (see "Starting point") and zip them up with a `pack.json`.
2. Edit the GLSL.
3. Drag the ZIP onto the shader menu (**I**), or put it in `shaderpacks/` in your game folder; choose it and press **Apply**.
4. After editing the ZIP again, press **R** (Reload Current Shaders) in game.

## What a pack looks like

```
MyPack.zip
├── pack.json
└── shaders/
    ├── override/
    │   ├── minecraft/core/   block, item, entity, particle, rendertype_clouds   (.vsh and .fsh)
    │   └── sodium/blocks/    block_layer_opaque                                  (.vsh and .fsh)
    ├── program/              this mod's own passes (see the table below)
    └── lib/                  anything your shaders #include
```

`pack.json` is `{"format": 1}`. Other fields are allowed and ignored. A different `format` number is refused, so an old
version of the mod never half-understands a newer pack. There is no author, version or ID field: the **name of the ZIP
file** (without `.zip`) is the pack's name in the menu.

## Options your pack offers

A pack can list settings in `pack.json`. They show up under **Shader Options... > "<your pack> Options..."**, are
saved per pack (in `config/metallum-extra-packs.properties`), and reach your shaders as `#define`s.

```json
{
  "format": 1,
  "options": [
    {"id": "QUALITY", "name": "Quality", "values": ["Low", "Medium", "High", "Ultra"], "default": "High"},
    {"id": "BLOOM", "name": "Bloom", "type": "toggle", "default": true},
    {"id": "GRAIN", "name": "Film Grain", "type": "toggle"}
  ]
}
```

- `id`: capital letters, digits and `_`, starting with a letter; the shaders see `OPTION_<id>`. Required, and unique.
- `name`: the label in the menu (the id if left out).
- A **choice** (the default type) has `values`, 2 to 16 texts, and an optional `default` that is one of them (the first
  if left out). The define is the value's position, counting from 0: `OPTION_QUALITY` is 2 for `High`.
- A **toggle** (`"type": "toggle"`) is Off (0) or On (1); `default` is `true` or `false` (false if left out).
- Up to 64 options; 12 are shown per page.

The mod puts the defines right after the first line of every shader of the pack, which is why that line must be the
`#version`. Use them like any other define:

```glsl
#if OPTION_QUALITY >= 2
    // shadows with more samples
#endif
#if OPTION_BLOOM == 1
    color += glow;
#endif
```

Changes are saved as they are made, and the shaders are compiled again when the player leaves the options screen. Every
shader is read again with the new defines, so the first change to a value you have not used before takes a moment; the
Metal translations of the results are cached, so going back is instant. A pack with a mistake in an option in
`pack.json` is shown in the menu with the reason and cannot be chosen.

This is also how one pack serves several quality levels: one ZIP, with a `QUALITY` choice, instead of one ZIP per level.

The ZIP must have `pack.json` and `shaders/` at its top level, not inside a folder. (Zipping a folder on macOS usually adds
that folder; zip its *contents*: `cd MyPack && zip -r ../MyPack.zip .`)

### A pack is complete and alone

Your pack supplies **every** shader the pipeline asks for. Nothing is ever taken from the built-in shaders or from another
pack, and shaders are never merged. If a required file is missing, or an `#include` names a file that is not in your ZIP,
the pack is refused: it shows in red in the menu with the reason and cannot be chosen.

The files that must exist are listed in `PackManager.REQUIRED` (24 files):

| File | What it draws |
|---|---|
| `override/sodium/blocks/block_layer_opaque.vsh/.fsh` | Terrain: solid, cut-out and translucent (water, glass). Replaces Sodium's own terrain shader. |
| `override/minecraft/core/block.vsh/.fsh` | Blocks drawn one at a time (falling sand, piston blocks, block entities) |
| `override/minecraft/core/entity.vsh/.fsh` | Mobs, players, armor, most other models |
| `override/minecraft/core/item.vsh/.fsh` | Items in the world, in frames and in hands |
| `override/minecraft/core/particle.vsh/.fsh` | Particles, rain and snow |
| `override/minecraft/core/rendertype_clouds.vsh/.fsh` | Clouds |
| `program/fullscreen.vsh` | The vertex shader of every full-screen pass: one triangle covering the screen |
| `program/sky.fsh` | The sky, drawn behind everything when the camera is under an open sky |
| `program/shadow_terrain.vsh/.fsh` | Terrain as the sun or moon sees it, into the shadow map |
| `program/effects.fsh` | Sun rays (color) and ambient occlusion (alpha), at half size |
| `program/effects_blur.fsh` | Softens that half-size image without smearing across depth edges |
| `program/effects_composite.fsh` | Lays the effects over the world image |
| `program/bloom_prefilter.fsh`, `bloom_downsample.fsh`, `bloom_upsample.fsh`, `bloom_composite.fsh` | The four steps of glow |
| `program/edges.fsh` | Last pass over the world image: smooths stair-stepped edges (Smooth Edges) |

A pass that the player has switched off in Shader Options (or that does not apply right now) is simply not run, but its file
must still be in the pack so that turning the option on never leaves a hole.

Files you do not need to touch can be copied unchanged from the built-in pack. You are free to reorganize `lib/` however you
like (folders, new files), as long as every `#include` resolves inside your ZIP.

## How the files are used

**`override/<namespace>/<path>`** replaces the shader that the game or another mod registered as `<namespace>:<path>`, only
while shaders are on. The pipeline stays theirs (vertex format, blending, uniforms); only the text changes. A pack can only
replace the shaders listed above. A shader of some other mod that has no file here is left as it is.

**`program/<name>`** is a shader of this mod's own passes, asked for as `metallum-extra:<name>`.

**Includes.** A line `#include "name.glsl"` is replaced by the file `lib/name.glsl` of your pack. Each library file goes in
once per shader, however many files ask for it, so library files may include what they need. Includes may be nested. The
include is textual: there is no include guard to write.

**Every shader file must start with its `#version` line** (the built-in ones use `#version 330`). The game inserts a
pipeline's `#define`s right after the first line. Comments are stripped before compiling. Shaders are compiled as GLSL, converted to
SPIR-V, then to Metal text by SPIRV-Cross.

## What your shaders can read

Each pipeline declares its own uniforms and samplers (the game's `Sampler0`, Sodium's `u_BlockTex`, and so on) and you can
only use ones that pipeline declares: see the built-in shader of the same name for the exact set. On top of that, **any** shader
of the pack may use these, which the mod fills in itself:

| Name | Kind | What |
|---|---|---|
| `MxGlobals` | uniform block (`std140`) | Per-frame values: the camera matrices, sun, moon and sky colors, shadow matrix, time, screen size, which effects are on. Declared for you by `#include "globals.glsl"`; read that file, every field is commented. |
| `MxShadowMap` | sampler2D | The shadow map's depth |
| `MxSceneColor` | sampler2D | A copy of the world image drawn so far (what water reflects and refracts) |
| `MxSceneDepth` | sampler2D | The matching depth |
| `MxLightColors` | sampler2D | A grid of block-light colors around the player (`light_colors.glsl`) |

`MxGlobals` includes, among others: `MxLightDir`/`MxLightColor` (the sun or moon, whichever lights the world),
`MxSkyZenith`/`MxSkyHorizon`/`MxSunsetColor`, `MxCameraPos` (`w` is seconds, for animation), `MxScreen`, `MxShadowMat`, and
`MxFeatures` and `MxParams2` which carry the player's toggles (sun rays, ambient occlusion, colored light, water
reflections, waving), so a pack can follow the options in Shader Options. `MxParams.x` is the **phase**: `mx_in_world()` is
true while the world is drawn; menus, the inventory and other screens use the game's colors, and the shaders must give the
game's own result there (see the built-in `override` shaders).

The textures the game and Sodium bind are limited by Metal: at most **16 textures per shader stage**.

Full-screen passes read the world through `InSampler` (the image being processed) and write `fragColor`. They run in this
order after the world is drawn: effects (sun rays and occlusion: `effects`, `effects_blur`, `effects_composite`), then glow
(`bloom_*`), then `edges`, then the held item is drawn.

The shadow map is drawn with `program/shadow_terrain.*` for terrain and with each `override` vertex shader for models:
a vertex shader must put its vertices where the light sees them while `MxParams.x` is the shadow phase. Draws whose shader
does not use `MxGlobals` are skipped while the map is drawn, so text and lines never scribble on it. Look at the built-in
`model.glsl` and `shadow_terrain.vsh`.

Colors: the world image holds display (sRGB-like) colors; lighting is added in linear light. `color.glsl` has the conversions.

## Starting point

The built-in pack is inside the mod's jar at `assets/metallum-extra/shaders/`:

```bash
mkdir MyPack && cd MyPack
unzip -q /path/to/metallum-extra-*.jar 'assets/metallum-extra/shaders/*' -d .
mv assets/metallum-extra/shaders shaders && rm -r assets
echo '{"format": 1}' > pack.json
zip -qr ../MyPack.zip .
```

or copy `src/main/resources/assets/metallum-extra/shaders/` from the source. Then change what you like.

### The smallest possible change

`program/edges.fsh` runs over the whole picture when Smooth Edges is on, which makes it a good first test. This version turns
red areas blue:

```glsl
#version 330

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    if (color.r > 0.5 && color.r > color.g * 1.5 && color.r > color.b * 1.5) {
        color.rgb = vec3(0.0, 0.0, color.r);
    }
    fragColor = color;
}
```

(The project's test packs do exactly this: `gradlew writeTestPacks -Pto=<folder>` writes `RedToBlue.zip` and `BlueToRed.zip`,
plus `SyntaxError.zip` and `Incomplete.zip` for seeing how mistakes are handled.)

## Working on a pack

- **Reload with R** (Reload Current Shaders, in Controls under Miscellaneous; it does nothing while shaders are off). It reads the folder and the pack's ZIP again
  and recompiles. Alternatively press **Apply** in the menu with the pack still highlighted.
- A pack is read into memory completely when it is chosen, so replacing the ZIP on disk never disturbs a running pack; the
  change shows after R or Apply.
- **Mistakes** show up when a shader is first compiled, which can be a moment after switching, in the middle of play. If
  compiling fails, the pack is put aside, the one that was working before (or the built-in shaders) takes over at once, and
  the game keeps running. The log has `Shader pack X is broken and is switched off:` followed by the shader's name and the
  compiler's message with a line number, and the menu shows the same in red under the list. Fix the file, zip again, choose
  the pack again.
- A pack that is merely incomplete never gets that far: it is refused when the menu reads the folder, with the missing file named.
- Packs can be quick to try because the translation to Metal text is cached on disk (`cache/metallum-extra/shaders/`): only
  shaders whose text actually changed are translated again. The log prints `MSL cache hits: N, MSL translations: M`.

### Development options

Start the game with these JVM arguments:

| Argument | Effect |
|---|---|
| `-Dmetallumextra.shaderDir=<folder>` | Read the **built-in** pack from a folder instead of the jar, so it can be edited without zipping (press R to reload) |
| `-Dmetallumextra.dumpShaders=<folder>` | Write the text of every shader the game compiles (as it was before replacing) to a folder; useful for seeing what a pipeline declares |
| `-Dmetallumextra.debugScript=<file>` | Run a script of steps (switch pack, take a screenshot, ...). The steps are listed at the top of `DebugScript.java` |
| `-Dmetallumextra.noMslCache=true` | Do not use the translation cache |

## Limits of format 1

- Packs use this mod's pipeline as it is: you can change what every pass computes, but not add passes, add uniforms,
  change a pipeline's vertex format, or change render targets.
- A pack's own options are choices and toggles only (no sliders), and they are compile-time defines, so changing one
  recompiles the shaders. The mod's own Shader Options (quality, shadows, glow, ...) reach your shaders through
  `MxGlobals` and are shown for every pack.
- No inheritance or merging between packs, and no shader downloads.
- Only Metal on macOS, and only with Sodium. Not together with Shine (see details.md).
