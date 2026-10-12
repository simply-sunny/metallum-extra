package com.metallumextra;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Plain-text config at config/metallum-extra.properties.
 * Missing keys fall back to defaults; the file is (re)written with every key so new options show up after updates.
 */
public final class ExtraConfig {
    private static final String FILE_NAME = "metallum-extra.properties";
    private static final String UPSCALE_PACK_PREFIX = "upscale.mode.";
    private static ExtraConfig instance;

    /** Master switch for the frame-hitch profiler. */
    public volatile boolean profilerEnabled;
    /** A frame only counts as a hitch if it is at least this long... */
    public final double hitchMinMs;
    /** ...and at least this many times longer than the recent average frame. */
    public final double hitchMultiplier;
    /** How often to log a summary line (avg FPS, 1% lows, hitch causes). */
    public final int summarySeconds;
    /** Cap on individual hitch lines in the game log per summary window (all hitches still go to the CSV). */
    public final int maxHitchLogsPerSummary;
    /** The run log: every setting, each change to one, and the frame rate per settings combination (see {@link RunLog}). */
    public final boolean runLogEnabled;
    /** Seconds between the run log's frame-rate lines. */
    public final int runLogPerfSeconds;
    /** With vsync off, skip presenting a frame instead of blocking when macOS has no swapchain image free. */
    public volatile boolean nonBlockingPresent;
    /** When the camera enters a new chunk section, update only the section slots that changed (vanilla rescans all). */
    public volatile boolean fastSectionRecenter;
    /** Sodium: empty its freed-buffer queue a little each frame instead of all at once after a GC cycle. */
    public volatile boolean spreadSodiumCleanup;
    /** Renumber samplers so shaders with textures in slots 16 and up compile (Metal has 16 sampler slots). File only; read once at startup. */
    public volatile boolean manyTextures;
    /** The built-in shader pipeline: own lighting, shadows, sky, water and bloom. Needs Sodium. */
    public volatile boolean shadersEnabled;
    /** A line in the chat when shaders are reloaded with the key. */
    public volatile boolean shaderMessages;
    /** Draws the world smaller and scales it up with MetalFX (see {@link com.metallumextra.fx.Upscale}). */
    public volatile com.metallumextra.fx.UpscaleMode upscaleMode;
    /** MetalFX per shader pack (file keys {@code upscale.mode.<pack>}); a pack without one of its own is Off. */
    public final java.util.Map<String, com.metallumextra.fx.UpscaleMode> upscaleModeByPack = new java.util.concurrent.ConcurrentHashMap<>();
    /** The shader pack in use: the file name of a ZIP in the shaderpacks folder, or {@code builtin}. Kept while shaders are off. */
    public volatile String shaderPack;

    private Path file;

    private ExtraConfig(final Properties p) {
        this.profilerEnabled = bool(p, "profiler.enabled", false);
        this.hitchMinMs = dbl(p, "profiler.hitchMinMs", 15.0);
        this.hitchMultiplier = dbl(p, "profiler.hitchMultiplier", 4.0);
        this.summarySeconds = Math.max(1, (int) dbl(p, "profiler.summarySeconds", 10));
        this.maxHitchLogsPerSummary = Math.max(0, (int) dbl(p, "profiler.maxHitchLogsPerSummary", 10));
        this.runLogEnabled = bool(p, "runlog.enabled", true);
        this.runLogPerfSeconds = Math.max(1, (int) dbl(p, "runlog.perfSeconds", 5));
        this.nonBlockingPresent = bool(p, "fix.nonBlockingPresent", true);
        this.fastSectionRecenter = bool(p, "fix.fastSectionRecenter", true);
        this.spreadSodiumCleanup = bool(p, "fix.spreadSodiumCleanup", true);
        this.manyTextures = bool(p, "compat.manyTextures", true);
        this.shadersEnabled = bool(p, "shaders.enabled", false);
        this.shaderMessages = bool(p, "shaders.messages", true);
        this.upscaleMode = com.metallumextra.fx.UpscaleMode.of(p.getProperty("upscale.mode", "OFF"));
        for (final String key : p.stringPropertyNames()) {
            if (key.startsWith(UPSCALE_PACK_PREFIX)) {
                this.upscaleModeByPack.put(key.substring(UPSCALE_PACK_PREFIX.length()), com.metallumextra.fx.UpscaleMode.of(p.getProperty(key, "OFF")));
            }
        }
        String pack = p.getProperty("shaders.pack", "builtin").strip();
        this.shaderPack = pack.isEmpty() ? "builtin" : pack;
    }

    /** Which preset the shader settings add up to; see {@link Quality}. */
    public Quality shaderQuality() {
        return Quality.of();
    }

    /** Writes a preset's values into the pack's options. Custom changes nothing. */
    public void setShaderQuality(final Quality quality) {
        if (quality == Quality.CUSTOM) return;
        quality.apply();
        MetallumExtra.LOGGER.info("[Metallum Extra] shader quality={}", quality.label);
    }

    /** The shader settings are read every frame, so these only have to store the value. */
    public void setShadersEnabled(final boolean value) {
        this.shadersEnabled = value;
        save(file);
        MetallumExtra.LOGGER.info("[Metallum Extra] shadersEnabled={}", value);
    }

    public void setUpscaleMode(final com.metallumextra.fx.UpscaleMode value) {
        if (this.shadersEnabled) {
            final String key = packKey(this.shaderPack);
            this.upscaleModeByPack.put(key, value);
            MetallumExtra.LOGGER.info("[Metallum Extra] upscaleMode[{}]={}", key, value);
        } else {
            this.upscaleMode = value;
            MetallumExtra.LOGGER.info("[Metallum Extra] upscaleMode={}", value);
        }
        save(file);
    }

    /** The MetalFX value for what is on right now: the pack's own while shaders are on, the no-shader one otherwise. */
    public com.metallumextra.fx.UpscaleMode effectiveUpscaleMode() {
        if (!this.shadersEnabled) return this.upscaleMode;
        return this.upscaleModeByPack.getOrDefault(packKey(this.shaderPack), com.metallumextra.fx.UpscaleMode.OFF);
    }

    /** The name a shader pack is remembered under in the file. */
    public static String packKey(final String pack) {
        return pack.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public void setShaderMessages(final boolean value) {
        this.shaderMessages = value;
        save(file);
    }

    public void setShaderPack(final String value) {
        this.shaderPack = value;
        save(file);
        MetallumExtra.LOGGER.info("[Metallum Extra] shaderPack={}", value);
    }

    /** Live toggles (from the in-game screen): take effect immediately and are written back to the file. */
    public void setFastSectionRecenter(final boolean value) {
        this.fastSectionRecenter = value;
        save(file);
        MetallumExtra.LOGGER.info("[Metallum Extra] fastSectionRecenter={}", value);
    }

    public void setSpreadSodiumCleanup(final boolean value) {
        this.spreadSodiumCleanup = value;
        save(file);
        MetallumExtra.LOGGER.info("[Metallum Extra] spreadSodiumCleanup={}", value);
    }

    public void setProfilerEnabled(final boolean value) {
        this.profilerEnabled = value;
        save(file);
        MetallumExtra.LOGGER.info("[Metallum Extra] profilerEnabled={} (takes effect after a restart)", value);
    }

    public void setNonBlockingPresent(final boolean value) {
        this.nonBlockingPresent = value;
        save(file);
        MetallumExtra.LOGGER.info("[Metallum Extra] nonBlockingPresent={}", value);
    }

    public static synchronized ExtraConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ExtraConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                props.load(r);
            } catch (IOException e) {
                MetallumExtra.LOGGER.warn("[Metallum Extra] Could not read {}, using defaults", file, e);
            }
        }
        ExtraConfig config = new ExtraConfig(props);
        config.file = file;
        config.save(file);
        return config;
    }

    private void save(final Path file) {
        final StringBuilder packLines = new StringBuilder();
        for (final var entry : new java.util.TreeMap<>(this.upscaleModeByPack).entrySet()) {
            packLines.append(UPSCALE_PACK_PREFIX).append(entry.getKey()).append('=').append(entry.getValue().name()).append('\n');
        }
        String text = """
                # Metallum Extra configuration. Restart the game after editing this file.
                # The same options are in game: Video Settings > Metallum Extra (with Sodium), or Mods > Metallum Extra
                # (with Mod Menu). The fix.* ones switch live.

                # --- Performance logging (off by default; restart needed) ---
                # For troubleshooting only. Records where every stutter's time went, to the game log
                # and to files in <minecraft folder>/metallum-extra/. Leave off unless you are asked
                # to turn it on: it writes new files every session.
                profiler.enabled=%s
                # A frame is a "hitch" when it is longer than BOTH of these:
                profiler.hitchMinMs=%s
                profiler.hitchMultiplier=%s
                # Seconds between summary lines (avg FPS, 1%% / 0.1%% lows, hitch causes).
                profiler.summarySeconds=%d
                profiler.maxHitchLogsPerSummary=%d

                # --- Run log (on by default) ---
                # Writes <minecraft folder>/metallum-extra/runlog-<time>.log for each launch: every setting at the
                # start (vanilla options, window size and focus, Sodium, this mod), each one that changes, and the
                # frame rate per combination of render distance, resolution, transparency, shaders and focus.
                runlog.enabled=%s
                # Seconds between frame-rate lines in the run log.
                runlog.perfSeconds=%d

                # --- Shaders (switch live; need Sodium) ---
                # Metallum Extra's own lighting: sun and moon light with shadows, a new sky, lit water with
                # reflections, sun rays, and glow around bright things. Off by default. What the shaders show
                # (shadows, glow, reflections ...) are the shader pack's own options: change them in game under
                # Shader Options, where the Quality setting (Low, Medium, High, Ultra) sets them together.
                shaders.enabled=%s
                # The shader pack in use: the file name of a ZIP in the shaderpacks folder, or builtin. Choose
                # one in game under Shader Packs. Switching shaders off does not forget it.
                shaders.pack=%s
                # Say "Shaders reloaded" in the chat when the reload key is pressed.
                shaders.messages=%s

                # --- MetalFX upscaling (switches live) ---
                # Draws the world at a smaller size and scales it up to the window with Apple's MetalFX spatial scaler;
                # the menus and the HUD stay sharp. OFF, QUALITY (1.5x), BALANCED (1.7x), PERFORMANCE (2x) or ULTRA_PERFORMANCE (3x).
                # This value is the one for no shader. Each shader pack has its own, written below as upscale.mode.<pack>
                # when set while that pack is on; a pack with none of its own is Off.
                upscale.mode=%s
                %s

                # --- Fixes ---
                # With vsync off, never stall the render thread waiting for the display. Frames that
                # finish while macOS has no swapchain image free are not shown (the next one is).
                # Has no effect with vsync on.
                fix.nonBlockingPresent=%s
                # Minecraft rescans every section slot in render distance each time you cross into a new
                # chunk section (millions of slots at very high render distances, e.g. with Bobby).
                # This updates only the slots that actually changed.
                fix.fastSectionRecenter=%s
                # Sodium empties a queue of finished chunk-mesh buffers in one go after each garbage
                # collection, which can take 50-130 ms while chunks stream in. This spreads that work
                # over the following frames. No effect without Sodium.
                fix.spreadSodiumCleanup=%s

                # --- Metallum limits lifted (file only, no in-game switch; restart needed) ---
                # These do nothing unless a mod needs them, so they are always on. They are here only so
                # one can be switched off if it ever causes trouble with some other mod.
                # Metal has 16 sampler slots per shader and Metallum numbers a sampler after its texture, so
                # a shader whose textures go past number 15 fails to compile ("'sampler' attribute parameter
                # is out of bounds"). This renumbers the samplers of such shaders. Others are untouched.
                compat.manyTextures=%s
                """.formatted(profilerEnabled, hitchMinMs, hitchMultiplier, summarySeconds, maxHitchLogsPerSummary,
                runLogEnabled, runLogPerfSeconds, shadersEnabled, shaderPack.replace("\\", "\\\\"), shaderMessages, upscaleMode.name(), packLines, nonBlockingPresent, fastSectionRecenter, spreadSodiumCleanup,
                manyTextures);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write(text);
            }
        } catch (IOException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Could not write {}", file, e);
        }
    }

    private static boolean bool(final Properties p, final String key, final boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static double dbl(final Properties p, final String key, final double def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
