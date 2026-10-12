package com.metallumextra;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The run log: everything the game and its mods hold as settings, and how fast frames came out under each combination of them.
 * <p>
 * Written to {@code <minecraft folder>/metallum-extra/runlog-<time>.log}, one file per launch:
 * <ul>
 *     <li>{@code SETTINGS}: every setting at the start. Vanilla options (all of them, read from the game's own option fields), the window (size, scale,
 *     focus), Metallum Extra's config and what it is doing, and Sodium's options (read from its options file, so they change when Sodium saves).</li>
 *     <li>{@code CHANGE}: a setting that changed, as {@code key: old -> new}, with the frame it changed on.</li>
 *     <li>{@code ENTER} and {@code LEFT}: a settings combination starting and ending (render distance, resolution, improved transparency, shaders,
 *     upscaling, focus, the open screen), with the frame rate it had.</li>
 *     <li>{@code PERF}: one line every {@code runlog.perfSeconds} with the frame rate, median, percentiles and 1% low of that window, the GC and heap.</li>
 *     <li>{@code TOTAL}: at exit, the frame rate of every settings combination the session was in.</li>
 * </ul>
 * Frame times are the gap between two presented frames. Settings are checked four times a second, so a change is logged up to a quarter of a second late.
 * Runs on the render thread (called when a frame is presented), so it only reads and writes; it does no game work.
 */
public final class RunLog {
    private static final long POLL_NANOS = 250_000_000L;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** The settings that make up a combination; a frame is counted under the combination of the last check. */
    private static final String[] COMBINATION_KEYS = {
            "game.menu", "options.renderDistance", "options.simulationDistance", "options.improvedTransparency", "options.cloudStatus",
            "options.enableVsync", "options.framerateLimit", "display.framebuffer", "display.focused", "mod.shadersEnabled", "mod.shaderPack",
            "mod.upscaleEffective"
    };
    private static final Object LOCK = new Object();

    private static boolean disabled;
    private static boolean started;
    private static BufferedWriter out;
    private static Path file;
    private static long startNanos;
    private static long lastPoll;
    private static long lastPerf;
    private static long windowStart;
    private static long lastFrame;
    private static long frameNumber;
    private static int perfSeconds;
    private static Map<String, String> settings;
    private static String combination = "";
    private static final FrameStats SINCE_ENTER = new FrameStats();
    private static final FrameStats SINCE_PERF = new FrameStats();
    private static FrameStats forCombination = new FrameStats();
    private static final Map<String, FrameStats> TOTALS = new TreeMap<>();
    private static long gcCount;
    private static long gcMs;
    private static List<Field> optionFields;
    private static long sodiumModified = -1;
    private static Map<String, String> sodiumFlat = Map.of();

    private RunLog() {
    }

    /** Called when a frame has been presented. */
    public static void frame() {
        if (disabled) {
            return;
        }
        long now = System.nanoTime();
        if (!started && !start(now)) {
            return;
        }
        frameNumber++;
        if (lastFrame != 0) {
            double ms = (now - lastFrame) / 1.0e6;
            SINCE_PERF.add(ms);
            SINCE_ENTER.add(ms);
            forCombination.add(ms);
        }
        lastFrame = now;
        if (now - lastPoll >= POLL_NANOS) {
            lastPoll = now;
            poll(now);
        }
        if (now - lastPerf >= perfSeconds * 1_000_000_000L) {
            lastPerf = now;
            perfLine(now);
        }
    }

    private static boolean start(final long now) {
        ExtraConfig config = ExtraConfig.get();
        if (!config.runLogEnabled) {
            disabled = true;
            return false;
        }
        perfSeconds = config.runLogPerfSeconds;
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("metallum-extra");
            Files.createDirectories(dir);
            file = dir.resolve("runlog-" + LocalDateTime.now().format(FILE_TIME) + ".log");
            out = Files.newBufferedWriter(file);
        } catch (IOException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Run log could not be written", e);
            disabled = true;
            return false;
        }
        started = true;
        startNanos = now;
        lastPoll = now - POLL_NANOS;
        lastPerf = now;
        windowStart = now;
        Runtime.getRuntime().addShutdownHook(new Thread(RunLog::close, "metallum-extra-run-log"));
        line("run log " + file + ": settings checked every 0.25 s, PERF every " + perfSeconds + " s");
        MetallumExtra.LOGGER.info("[Metallum Extra] Run log: {}", file);
        return true;
    }

    private static void poll(final long now) {
        Map<String, String> snapshot = snapshot();
        if (settings == null) {
            line("SETTINGS at start, " + snapshot.size() + " values:");
            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                line("  " + entry.getKey() + " = " + entry.getValue());
            }
        } else {
            for (String change : SettingsDiff.diff(settings, snapshot)) {
                line("CHANGE " + change + " (frame " + frameNumber + ")");
                MetallumExtra.LOGGER.info("[Metallum Extra] run log: {}", change);
            }
        }
        String next = combinationOf(snapshot);
        if (!next.equals(combination)) {
            if (!combination.isEmpty()) {
                line("LEFT " + combination + " :: " + describe(SINCE_ENTER));
            }
            combination = next;
            SINCE_ENTER.reset();
            forCombination = TOTALS.computeIfAbsent(combination, key -> new FrameStats());
            line("ENTER " + combination);
        }
        settings = snapshot;
    }

    private static void perfLine(final long now) {
        long gcCountNow = 0;
        long gcMsNow = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountNow += Math.max(0, gc.getCollectionCount());
            gcMsNow += Math.max(0, gc.getCollectionTime());
        }
        long gcDelta = gcCountNow - gcCount;
        long gcMsDelta = gcMsNow - gcMs;
        gcCount = gcCountNow;
        gcMs = gcMsNow;
        Runtime runtime = Runtime.getRuntime();
        long heapMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        if (SINCE_PERF.count() > 0) {
            line("PERF " + describe(SINCE_PERF) + String.format(Locale.ROOT, " gc=+%dx/+%dms heapMB=%d gpuMB=%d window=%.1fs",
                    gcDelta, gcMsDelta, heapMb, FrameProfiler.gpuMemoryMb(), (now - windowStart) / 1.0e9)
                    + " :: " + combination);
        }
        SINCE_PERF.reset();
        windowStart = now;
    }

    /** One frame line: count, FPS, median, 95th and 99th percentile, 1% low, worst. */
    static String describe(final FrameStats stats) {
        return String.format(Locale.ROOT, "frames=%d fps=%.1f median=%.2fms p95=%.2fms p99=%.2fms 1%%low=%.1ffps max=%.1fms",
                stats.count(), stats.fps(), stats.percentileMs(0.5), stats.percentileMs(0.95), stats.percentileMs(0.99), stats.onePercentLowFps(),
                stats.maxMs());
    }

    /** The values that make up a combination, in {@link #COMBINATION_KEYS} order, as {@code name=value}. */
    static String combinationOf(final Map<String, String> snapshot) {
        StringBuilder text = new StringBuilder();
        for (String key : COMBINATION_KEYS) {
            String value = snapshot.get(key);
            if (value == null) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(key.substring(key.indexOf('.') + 1)).append('=').append(value);
        }
        return text.toString();
    }

    /** Every setting now, by name. */
    static Map<String, String> snapshot() {
        Map<String, String> values = new TreeMap<>();
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;
        for (Field field : optionFields()) {
            try {
                Object value = ((OptionInstance<?>) field.get(options)).get();
                values.put("options." + field.getName(), String.valueOf(value));
            } catch (IllegalAccessException | RuntimeException e) {
                values.put("options." + field.getName(), "unreadable");
            }
        }
        Window window = minecraft.getWindow();
        values.put("display.framebuffer", window.getWidth() + "x" + window.getHeight());
        values.put("display.screen", window.getScreenWidth() + "x" + window.getScreenHeight());
        values.put("display.guiScale", String.valueOf(window.getGuiScale()));
        values.put("display.focused", String.valueOf(window.isFocused()));
        values.put("display.iconified", String.valueOf(window.isIconified()));
        net.minecraft.client.gui.screens.Screen screen = minecraft.gui.screen();
        values.put("game.screen", screen == null ? "none" : screen.getClass().getSimpleName());
        values.put("game.menu", screen == null ? "no" : "yes");
        values.put("game.inWorld", String.valueOf(minecraft.level != null));

        ExtraConfig config = ExtraConfig.get();
        values.put("mod.profiler", String.valueOf(config.profilerEnabled));
        values.put("mod.nonBlockingPresent", String.valueOf(config.nonBlockingPresent));
        values.put("mod.fastSectionRecenter", String.valueOf(config.fastSectionRecenter));
        values.put("mod.spreadSodiumCleanup", String.valueOf(config.spreadSodiumCleanup));
        values.put("mod.manyTextures", String.valueOf(config.manyTextures));
        values.put("mod.shadersEnabled", String.valueOf(config.shadersEnabled));
        values.put("mod.shaderPack", config.shaderPack);
        values.put("mod.shaderQuality", String.valueOf(config.shaderQuality()));
        values.put("mod.shaderMessages", String.valueOf(config.shaderMessages));
        values.put("mod.upscaleSetting", config.upscaleMode.name());
        values.put("mod.upscaleEffective", config.effectiveUpscaleMode().name());

        values.putAll(sodiumSettings());
        return values;
    }

    private static List<Field> optionFields() {
        if (optionFields == null) {
            List<Field> fields = new ArrayList<>();
            for (Field field : Options.class.getDeclaredFields()) {
                if (OptionInstance.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            optionFields = fields;
        }
        return optionFields;
    }

    /** Sodium's options, read from its file when the file has changed since the last check. */
    private static Map<String, String> sodiumSettings() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("sodium-options.json");
        try {
            if (!Files.exists(path)) {
                return Map.of();
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (modified != sodiumModified) {
                sodiumModified = modified;
                Map<String, String> flat = new TreeMap<>();
                flatten("sodium", JsonParser.parseString(Files.readString(path)), flat);
                sodiumFlat = flat;
            }
        } catch (IOException | RuntimeException e) {
            // Keep the last values read; the file is rewritten whole by Sodium and may be caught mid-write.
        }
        return sodiumFlat;
    }

    private static void flatten(final String prefix, final JsonElement element, final Map<String, String> out) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                flatten(prefix + "." + entry.getKey(), entry.getValue(), out);
            }
        } else {
            out.put(prefix, element.toString());
        }
    }

    private static void line(final String text) {
        synchronized (LOCK) {
            if (out == null) {
                return;
            }
            try {
                out.write(String.format(Locale.ROOT, "[+%8.2fs f%-7d] %s", (System.nanoTime() - startNanos) / 1.0e9, frameNumber, text));
                out.newLine();
                out.flush();
            } catch (IOException ignored) {
                // The game is running; a log line that cannot be written is not worth stopping for.
            }
        }
    }

    private static void close() {
        synchronized (LOCK) {
            if (out == null) {
                return;
            }
            line("SESSION END after " + frameNumber + " frames");
            line("TOTAL per combination:");
            for (Map.Entry<String, FrameStats> entry : TOTALS.entrySet()) {
                line("  " + entry.getKey() + " :: " + describe(entry.getValue()));
            }
            try {
                out.close();
            } catch (IOException ignored) {
                // Shutting down.
            }
            out = null;
        }
    }
}
