# Metallum Extra

Metallum Extra extends [Metallum](https://github.com/kokodio/metallum), an existing Metal rendering backend for Minecraft on macOS. This project adds client features to that renderer, including the **Metallically Beautiful** built-in shader pack, a shader-pack menu, pack options, and an experimental path for translating parts of the Iris/OptiFine shader-pack format into shaders Metallum can run on Metal.

The shader work is an ongoing compatibility layer around Metallum's renderer; it is not a replacement rendering backend and does not provide complete Iris compatibility. The current implementation handles selected GLSL translations, world-rendering programs, shadow maps, and screen-space passes. See the [current compatibility notes](docs/translation-table.md) and [shader-pack documentation](docs/shader-packs.md) for specific support and gaps.

## What works today

- **Built-in shaders:** Metallically Beautiful offers configurable shadows, reflections, bloom, ambient occlusion, waving blocks, sun rays, colored lighting, and edge smoothing.
- **Experimental Iris-style ZIP packs:** smoke-tested in-game with BSL v10.1.8 and Complementary Reimagined r5.5.1, plus one 40-second Complementary r5.9.3 Medium run, in the Nether. Pack behavior and supported features vary; a successful load does not mean every pass or dimension is supported.
- **Other Metal and Sodium improvements:** unlocked frame rate, smoother chunk crossing and memory cleanup, Distant Horizons and Shine compatibility, and optional performance logging.
- **Reproducible no-game checks:** stage ordering, render-target flips, legacy GLSL rewrites, and Metal translation-cache invalidation can be checked from a clean checkout with `./gradlew architectureTest` (JDK 25 required).

The [renderer demo and recorded measurements](docs/renderer-demo.md) show the tested packs and the limits of those results.

## Current limitations

Iris compatibility is partial and experimental. Unsupported shader stages and features are listed in the [translation table](docs/translation-table.md); these include compute and image operations, shader storage buffers, some extra shadow attachments, additional color buffers, and parts of Iris's uniform and screen configuration APIs. Packs may fail to load, render incorrectly, or fall back to the built-in shader path. The in-game pack results are a small compatibility sample, not a general compatibility claim.

## Install

Download the jar from [Releases](https://github.com/ohjey/metallum-extra/releases) and put it in your `mods` folder next to Metallum.

**Requires:** Minecraft 26.2, Fabric, Metallum 0.0.23 or 0.0.24, and Sodium for shaders.

## Build and verify

Needs JDK 25.

```bash
git clone https://github.com/ohjey/metallum-extra.git
cd metallum-extra
./gradlew build
./gradlew architectureTest
```

`architectureTest` runs pure planning and translation checks without starting Minecraft. The built jar is written to `build/libs/`.

For how the features work, configuration keys, shader-pack format, and release notes, see [docs/details.md](docs/details.md) and [docs/shader-packs.md](docs/shader-packs.md).

## License

MIT; see [LICENSE](LICENSE). Metallum Extra is an add-on for [Metallum](https://github.com/kokodio/metallum), which is also MIT licensed, and parts of it mirror fixes made in Metallum's own source.
