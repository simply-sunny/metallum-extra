package com.metallumextra.shader;

import com.metallumextra.ExtraConfig;
import com.metallumextra.ExtraConfigScreen;
import com.metallumextra.Quality;
import com.metallumextra.MetallumExtra;
import com.metallumextra.ShaderKeys;
import com.metallumextra.ShaderOptionsScreen;
import com.metallumextra.ShaderPackScreen;
import com.metallumextra.shader.pack.BuiltinOptions;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.TranslationCache;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Development aid, off unless the game is started with {@code -Dmetallumextra.debugScript=<file>}: runs a list of
 * steps once a world is on screen, so the picture can be checked without anyone at the keyboard. One step per line:
 * <pre>
 * world Test         first line only: opens the world of that name from the title screen, making it (creative, cheats on) if
 *                     there is none
 * wait 500           milliseconds to let pass
 * cmd time set 6000  a command, run by the integrated server
 * shot noon          saves the world image as noon.png next to the script
 * set shadows off    a shader setting: shaders, shadows, bloom, reflections, waving (on or off)
 * pos                logs where the player is
 * view back          third person from behind (front, or first to go back)
 * hud off            hides the HUD and chat (hud on shows them again)
 * screen settings    opens this mod's settings screen (screen packs: the shader menu, screen options: shader options; screen close closes whatever is open)
 * pack RedToBlue.zip  chooses a shader pack (builtin for the built-in one); the folder is read again first
 * sodium             opens Sodium's video settings; shadersontop opens the shader menu over the current screen; esc presses Escape and logs the screen after
 * drop <file>...     drops files onto the open screen
 * key reload         presses a key of this mod: reload, toggle or open
 * packs               logs the packs found and the one in use
 * cache               logs the translation cache counters (MSL cache hits and translations)
 * reload             reads the shader files again
 * trace 3           logs every program a standard shader pack runs for 3 frames, with the pixels each wrote
 * resize 800x450    sets the window size
 * perf 10000 name   measures frame times for that long (median and 95th percentile, logged and added to perf.txt)
 * screen inventory   opens the inventory
 * quit               closes the game
 * </pre>
 */
public final class DebugScript {
    private static final @Nullable Path FILE = file();
    private static @Nullable ArrayDeque<String> steps;
    /** The next step runs once the clock passes this. */
    private static long resumeAt;
    private static boolean worldOpened;
    /** A frame-time measurement in progress: its name, when it ends, and every frame's length so far, in milliseconds. */
    private static @Nullable String perfName;
    private static long perfEnd;
    private static long perfLast;
    private static final java.util.ArrayList<Double> PERF_FRAMES = new java.util.ArrayList<>();

    private DebugScript() {
    }

    static void tick() {
        if (FILE == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            openWorld(minecraft);
            return;
        }
        if (steps == null) {
            // Not before the world is on screen with nothing in front of it.
            if (minecraft.gui.screen() != null) return;
            try {
                steps = new ArrayDeque<>(Files.readAllLines(FILE));
                pause(1500);
            } catch (IOException e) {
                MetallumExtra.LOGGER.error("[Metallum Extra] Could not read debug script {}", FILE, e);
                steps = new ArrayDeque<>(List.of());
            }
        }
        samplePerf();
        while (System.nanoTime() >= resumeAt && !steps.isEmpty()) {
            run(minecraft, steps.poll().trim());
        }
    }

    /** From the title screen: the world a script asks for with a {@code world} line. */
    private static void openWorld(final Minecraft minecraft) {
        if (worldOpened || !minecraft.isGameLoadFinished() || minecraft.gui.overlay() != null || !(minecraft.gui.screen() instanceof TitleScreen title)) return;
        worldOpened = true;
        try {
            for (String line : Files.readAllLines(FILE)) {
                if (!line.trim().startsWith("world ")) continue;
                String name = line.trim().substring("world ".length()).trim();
                MetallumExtra.LOGGER.info("[Metallum Extra] debug script: opening world {}", name);
                WorldOpenFlows flows = minecraft.createWorldOpenFlows();
                if (minecraft.getLevelSource().levelExists(name)) {
                    flows.openWorld(name, () -> MetallumExtra.LOGGER.error("[Metallum Extra] debug script: could not open world {}", name));
                } else {
                    flows.createFreshLevel(name,
                            new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                            new WorldOptions(1234L, false, false), WorldPresets::createNormalWorldDimensions, title);
                }
                return;
            }
        } catch (IOException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] Could not read debug script {}", FILE, e);
        }
    }

    /** Called once per frame: adds the frame to the measurement in progress and ends it when its time is up. */
    private static void samplePerf() {
        if (perfName == null) return;
        long now = System.nanoTime();
        if (perfLast != 0) PERF_FRAMES.add((now - perfLast) / 1.0e6);
        perfLast = now;
        if (now < perfEnd) return;
        java.util.List<Double> sorted = PERF_FRAMES.stream().sorted().toList();
        if (!sorted.isEmpty()) {
            double median = sorted.get(sorted.size() / 2);
            double p95 = sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)));
            String line = String.format(java.util.Locale.ROOT, "perf %s: %d frames, median %.2f ms, 95th percentile %.2f ms", perfName, sorted.size(), median, p95);
            MetallumExtra.LOGGER.info("[Metallum Extra] debug script: {}", line);
            try {
                Files.writeString(FILE.resolveSibling("perf.txt"), line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                MetallumExtra.LOGGER.warn("[Metallum Extra] debug script: could not write perf.txt", e);
            }
        }
        perfName = null;
        PERF_FRAMES.clear();
    }

    /** What a picture was taken with, written next to it, so a later run can tell whether the scene is the same. */
    private static void writeSceneInfo(final Minecraft minecraft, final Path png) {
        ExtraConfig c = ExtraConfig.get();
        var target = minecraft.gameRenderer.mainRenderTarget();
        var level = minecraft.level;
        StringBuilder info = new StringBuilder();
        info.append("resolution ").append(target.width).append('x').append(target.height).append('\n');
        info.append("position ").append(minecraft.player.position()).append('\n');
        info.append("rotation ").append(minecraft.player.getYRot()).append(' ').append(minecraft.player.getXRot()).append('\n');
        info.append("dimension ").append(level.dimension().identifier()).append('\n');
        info.append("gameTime ").append(level.getGameTime()).append(" raining ").append(level.isRaining()).append('\n');
        info.append("pack ").append(PackManager.activeId()).append(" shaders ").append(c.shadersEnabled).append('\n');
        info.append("quality ").append(c.shaderQuality());
        for (var option : com.metallumextra.shader.pack.PackManager.active().options()) {
            info.append(' ').append(option.id()).append(' ').append(option.values().get(com.metallumextra.shader.pack.PackOptions.get(com.metallumextra.shader.pack.PackManager.active().name(), option)));
        }
        info.append('\n');
        try {
            Files.writeString(png.resolveSibling(png.getFileName().toString().replace(".png", ".txt")), info.toString());
        } catch (IOException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] debug script: could not write the scene info", e);
        }
    }

    private static void pause(final long milliseconds) {
        resumeAt = System.nanoTime() + milliseconds * 1_000_000L;
    }

    private static void run(final Minecraft minecraft, final String step) {
        if (step.isEmpty() || step.startsWith("#")) return;
        int space = step.indexOf(' ');
        String verb = space < 0 ? step : step.substring(0, space);
        String rest = space < 0 ? "" : step.substring(space + 1).trim();
        MetallumExtra.LOGGER.info("[Metallum Extra] debug script: {}", step);
        switch (verb) {
            case "world" -> {
            }
            case "wait" -> pause(Long.parseLong(rest));
            case "cmd" -> {
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server != null) {
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER).withSuppressedOutput(), rest));
                }
                pause(300);
            }
            case "shot" -> {
                Path target = FILE.resolveSibling(rest + ".png");
                writeSceneInfo(minecraft, target);
                Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> Util.ioPool().execute(() -> {
                    try (image) {
                        image.writeToFile(target);
                        MetallumExtra.LOGGER.info("[Metallum Extra] debug script: wrote {}", target);
                    } catch (IOException e) {
                        MetallumExtra.LOGGER.error("[Metallum Extra] debug script: could not write {}", target, e);
                    }
                }));
                pause(100);
            }
            case "set" -> {
                String[] parts = rest.split("\\s+");
                boolean on = parts.length > 1 && parts[1].equals("on");
                ExtraConfig config = ExtraConfig.get();
                switch (parts[0]) {
                    case "shaders" -> config.shadersEnabled = on;
                    case "shadows" -> BuiltinOptions.set("SHADOWS", on);
                    case "bloom" -> BuiltinOptions.set("BLOOM", on);
                    case "reflections" -> BuiltinOptions.set("WATER_REFLECTIONS", on);
                    case "waving" -> BuiltinOptions.set("WAVING", on);
                    case "rays" -> BuiltinOptions.set("SUN_RAYS", on);
                    case "ao" -> BuiltinOptions.set("AMBIENT_OCCLUSION", on);
                    case "colored" -> BuiltinOptions.set("COLORED_LIGHT", on);
                    case "edges" -> BuiltinOptions.set("SMOOTH_EDGES", on);
                    case "chat" -> config.shaderMessages = on;
                    case "quality" -> config.setShaderQuality(Quality.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT)));
                    default -> MetallumExtra.LOGGER.warn("[Metallum Extra] debug script: unknown setting {}", parts[0]);
                }
                pause(100);
            }
            case "screen" -> {
                minecraft.gui.setScreen(rest.equals("settings") ? new ExtraConfigScreen(null) : rest.equals("packs") ? new ShaderPackScreen(null) : rest.equals("options") ? new ShaderOptionsScreen(null, null)
                        : rest.equals("inventory") ? new net.minecraft.client.gui.screens.inventory.InventoryScreen(minecraft.player) : null);
                pause(300);
            }
            case "key" -> {
                // Presses one of this mod's keys, as the game would count it (reload, toggle or open).
                var key = switch (rest) {
                    case "reload" -> ShaderKeys.RELOAD;
                    case "toggle" -> ShaderKeys.TOGGLE;
                    default -> ShaderKeys.OPEN;
                };
                net.minecraft.client.KeyMapping.click(key.getDefaultKey());
                pause(500);
            }
            case "sodium" -> minecraft.gui.setScreen(net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(null));
            case "shadersontop" -> minecraft.gui.setScreen(new ShaderPackScreen(minecraft.gui.screen()));
            case "esc" -> {
                if (minecraft.gui.screen() != null) {
                    // A real tap is a press and then a release, each handled by whatever screen is showing then.
                    var event = new net.minecraft.client.input.KeyEvent(256, 0, 0);
                    minecraft.gui.screen().keyPressed(event);
                    if (minecraft.gui.screen() != null) minecraft.gui.screen().keyReleased(event);
                }
                MetallumExtra.LOGGER.info("[Metallum Extra] debug script: after Esc the screen is {}", minecraft.gui.screen() == null ? "none (the game)" : minecraft.gui.screen().getClass().getName());
            }
            case "drop" -> {
                // Drops files onto the shader menu, as the window would (paths separated by spaces).
                if (minecraft.gui.screen() != null) minecraft.gui.screen().onFilesDrop(java.util.Arrays.stream(rest.split(" ")).map(Path::of).toList());
            }
            case "pack" -> {
                PackManager.rescan();
                String error = PackManager.select(rest);
                if (error != null) MetallumExtra.LOGGER.warn("[Metallum Extra] debug script: pack {} not chosen: {}", rest, error);
                pause(300);
            }
            case "packopt" -> {
                // packopt <ID> <index>: sets an option of the pack in use and compiles its shaders again.
                String[] parts = rest.split("\\s+");
                for (var option : PackManager.active().options()) {
                    if (option.id().equals(parts[0])) {
                        com.metallumextra.shader.pack.PackOptions.set(PackManager.active().name(), option, Integer.parseInt(parts[1]));
                        Shaders.packOptionsChanged();
                    }
                }
                pause(300);
            }
            case "perf" -> {
                // perf <milliseconds> <name>: measures how long frames take for that long; the result is logged and added to perf.txt.
                String[] parts = rest.split("\\s+", 2);
                long milliseconds = Long.parseLong(parts[0]);
                perfName = parts.length > 1 ? parts[1] : "perf";
                perfLast = 0;
                PERF_FRAMES.clear();
                perfEnd = System.nanoTime() + milliseconds * 1_000_000L;
                pause(milliseconds + 100);
            }
            case "trace" -> {
                // trace <frames>: logs every program the shader pack runs, and what it drew, for that many frames.
                IrisPipeline.trace(Integer.parseInt(rest));
                pause(100);
            }
            case "resize" -> {
                // resize <width>x<height>: sets the window's size and logs the buffer sizes a moment later.
                String[] size = rest.split("x");
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(minecraft.getWindow().handle(), Integer.parseInt(size[0]), Integer.parseInt(size[1]));
                pause(1500);
                var main = minecraft.gameRenderer.mainRenderTarget();
                MetallumExtra.LOGGER.info("[Metallum Extra] debug script: window is now {}x{}", main.width, main.height);
            }
            case "packs" -> {
                PackManager.rescan();
                for (PackManager.Entry entry : PackManager.entries()) {
                    MetallumExtra.LOGGER.info("[Metallum Extra] debug script: pack {} ({}){}", entry.id(), entry.name(), entry.error() == null ? "" : " error: " + entry.error());
                }
                MetallumExtra.LOGGER.info("[Metallum Extra] debug script: in use {}", PackManager.activeId());
            }
            case "cache" -> MetallumExtra.LOGGER.info("[Metallum Extra] debug script: {}; {}", TranslationCache.summary(), ShadowPass.summary() + "; " + IrisPipeline.summary());
            case "pos" -> MetallumExtra.LOGGER.info("[Metallum Extra] debug script: player at {} looking {} / {}", minecraft.player.position(), minecraft.player.getYRot(), minecraft.player.getXRot());
            case "view" -> minecraft.options.setCameraType(rest.equals("back") ? CameraType.THIRD_PERSON_BACK : rest.equals("front") ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
            case "hud" -> {
                if (minecraft.gui.hud.isHidden() != rest.equals("off")) minecraft.gui.hud.toggle();
            }
            case "reload" -> {
                Shaders.reload();
                pause(100);
            }
            case "quit" -> minecraft.stop();
            default -> MetallumExtra.LOGGER.warn("[Metallum Extra] debug script: unknown step {}", step);
        }
    }

    private static @Nullable Path file() {
        String file = System.getProperty("metallumextra.debugScript");
        return file == null || file.isBlank() ? null : Path.of(file);
    }
}
