# Metallum Extra

An add-on for [Metallum](https://modrinth.com/mod/metallum-mc), the Metal backend for Minecraft on macOS.
It makes uncapped FPS steady on Apple Silicon, runs shader packs on Metal, and adds a built-in set of shaders.

**Requires:** Minecraft 26.3, Fabric, and Metallum 0.0.25. The shaders also need Sodium.

## Install
Download the jar from [Releases](https://github.com/ohjey/metallum-extra/releases) and put it in your `mods`
folder next to Metallum.

## Features
- **Shaders** (experimental, off by default): a built-in look with sun and moon shadows, reflective water, glow,
  colored light, sun rays, ambient occlusion and smooth edges. Quality presets go from Low to Ultra, and every
  option can be set on its own.
- **Shader packs:** ZIP packs in the Iris layout, from the `shaderpacks` folder, run on Metal. Support is partial:
  geometry and tessellation shaders do not run, and some Iris properties are missing. See the
  [pack guide](docs/shader-packs.md) and the [translation table](docs/translation-table.md).
- **MetalFX upscaling:** draws the world at a smaller size and scales it up to your window with Apple's MetalFX.
  Menus and the HUD stay sharp. Off by default, and chosen separately for each shader pack.
- **Unlocked frame rate:** with VSync off, the game no longer pauses to wait for the screen. Higher FPS and
  fewer small stutters.
- **Smooth chunk crossing:** removes the freeze when you move into a new chunk at very high render distances.
- **Smooth memory cleanup:** spreads Sodium's chunk-memory cleanup over many frames instead of one.
- **Logs:** each launch writes a run log of your settings and frame rates, on by default. The frame-hitch
  profiler is off by default and records what caused each stutter. Shader load costs go to a resource log in
  the `metallum-extra` folder of your game.

## Settings
Everything is switched in game, under **Video Settings → Metallum Extra** (with Sodium) or from Mod Menu.
Settings are saved to `config/metallum-extra.properties`, and each shader pack's options to
`config/metallum-extra-packs.properties`.

The shader keys are under **Controls → Miscellaneous**: `R` reloads the current shaders, `K` turns shaders on
and off, and `I` opens the shader menu.

## Building
Needs JDK 25.

```bash
git clone https://github.com/ohjey/metallum-extra.git
cd metallum-extra
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew architectureTest` runs the checks that do not need Minecraft.

How each feature works, the config keys and how to add a new Metallum release are in
[docs/details.md](docs/details.md). The code layout is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License
MIT; see [LICENSE](LICENSE). Metallum Extra is an add-on for [Metallum](https://github.com/kokodio/metallum),
which is also MIT licensed, and parts of it mirror fixes made in Metallum's own source.
