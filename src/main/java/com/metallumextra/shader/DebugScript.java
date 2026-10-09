package com.metallumextra.shader;

import com.metallumextra.ExtraConfig;
import com.metallumextra.ExtraConfigScreen;
import com.metallumextra.Quality;
import com.metallumextra.MetallumExtra;
import com.metallumextra.ShaderKeys;
import com.metallumextra.ShaderOptionsScreen;
import com.metallumextra.ShaderPackScreen;
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
 * quit               closes the game
 * </pre>
 */
public final class DebugScript {
    private static final @Nullable Path FILE = file();
    private static @Nullable ArrayDeque<String> steps;
    /** The next step runs once the clock passes this. */
    private static long resumeAt;
    private static boolean worldOpened;

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
                    case "shadows" -> config.shaderShadows = on;
                    case "bloom" -> config.shaderBloom = on;
                    case "reflections" -> config.shaderWaterReflections = on;
                    case "waving" -> config.shaderWaving = on;
                    case "rays" -> config.shaderSunRays = on;
                    case "ao" -> config.shaderAmbientOcclusion = on;
                    case "colored" -> config.shaderColoredLight = on;
                    case "edges" -> config.shaderSmoothEdges = on;
                    case "chat" -> config.shaderMessages = on;
                    case "quality" -> config.setShaderQuality(Quality.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT)));
                    default -> MetallumExtra.LOGGER.warn("[Metallum Extra] debug script: unknown setting {}", parts[0]);
                }
                pause(100);
            }
            case "screen" -> {
                minecraft.gui.setScreen(rest.equals("settings") ? new ExtraConfigScreen(null) : rest.equals("packs") ? new ShaderPackScreen(null) : rest.equals("options") ? new ShaderOptionsScreen(null, null) : null);
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
            case "packs" -> {
                PackManager.rescan();
                for (PackManager.Entry entry : PackManager.entries()) {
                    MetallumExtra.LOGGER.info("[Metallum Extra] debug script: pack {} ({}){}", entry.id(), entry.name(), entry.error() == null ? "" : " error: " + entry.error());
                }
                MetallumExtra.LOGGER.info("[Metallum Extra] debug script: in use {}", PackManager.activeId());
            }
            case "cache" -> MetallumExtra.LOGGER.info("[Metallum Extra] debug script: {}; {}", TranslationCache.summary(), ShadowPass.summary());
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
