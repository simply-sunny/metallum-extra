package com.metallumextra.shader.pack;

import com.metallumextra.MetallumExtra;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * What the player chose for each pack's options, kept in {@code config/metallum-extra-packs.properties}. Each pack has
 * its own values, found by the pack's name. A value is saved by its label, so a pack that reorders its values later does
 * not change what was chosen; one that renames or removes it falls back to its default.
 */
public final class PackOptions {
    private static final Properties CHOSEN = new Properties();
    private static boolean loaded;

    private PackOptions() {
    }

    private static Path file() {
        // Development and tests: keep the choices somewhere else than the game's config folder.
        String override = System.getProperty("metallumextra.packOptionsFile");
        if (override != null && !override.isBlank()) return Path.of(override);
        return FabricLoader.getInstance().getConfigDir().resolve("metallum-extra-packs.properties");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try (Reader in = Files.newBufferedReader(file)) {
            CHOSEN.load(in);
        } catch (IOException | RuntimeException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Could not read {}; pack options start from their defaults", file, e);
            CHOSEN.clear();
        }
    }

    private static String key(final String pack, final PackOption option) {
        return pack + "/" + option.id();
    }

    /** The index chosen for {@code option} of the pack called {@code pack}. */
    public static synchronized int get(final String pack, final PackOption option) {
        load();
        String value = CHOSEN.getProperty(key(pack, option));
        return value == null ? option.defaultIndex() : option.indexOf(value);
    }

    public static synchronized void set(final String pack, final PackOption option, final int index) {
        load();
        if (index == option.defaultIndex()) {
            CHOSEN.remove(key(pack, option));
        } else {
            CHOSEN.setProperty(key(pack, option), option.values().get(index));
        }
        save();
    }

    /** Puts every option of {@code pack} back to its default. */
    public static synchronized void reset(final String pack, final Iterable<PackOption> options) {
        load();
        for (PackOption option : options) CHOSEN.remove(key(pack, option));
        save();
    }

    private static void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer out = Files.newBufferedWriter(temp)) {
                CHOSEN.store(out, "Metallum Extra: what was chosen for each shader pack's own options");
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Could not write {}", file, e);
        }
    }
}
