package com.metallumextra.shader.pack;

import com.metallumextra.MetallumExtra;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The shader pack inside this mod's jar: an ordinary pack in the Iris layout, read from {@code assets/metallum-extra/pack/shaders/}. */
public final class BuiltinPack implements ShaderPack {
    public static final String NAME = "Metallically Beautiful - Internal";

    private static final String ROOT = "assets/metallum-extra/pack/shaders";
    /** Development aid: read the shaders from this folder. */
    private static final @Nullable Path DEV_DIR = devDir();

    private volatile @Nullable Map<String, String> files;
    private volatile @Nullable List<PackOption> options;

    private Map<String, String> contents() {
        Map<String, String> loaded = files;
        if (loaded == null || DEV_DIR != null) {
            loaded = readAll();
            files = loaded;
            options = StandardOptions.discover(loaded);
        }
        return loaded;
    }

    private static Map<String, String> readAll() {
        Map<String, String> result = new HashMap<>();
        Path root = DEV_DIR != null ? DEV_DIR : resourceRoot();
        if (root == null) return result;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                result.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] Could not read the built-in shaders", e);
        }
        return result;
    }

    private static @Nullable Path resourceRoot() {
        try {
            var container = FabricLoader.getInstance().getModContainer(MetallumExtra.MOD_ID);
            if (container.isPresent()) return container.get().findPath(ROOT).orElse(null);
        } catch (RuntimeException | LinkageError e) {
            // Not running under the mod loader (the unit tests): the resources are on the class path as plain files.
        }
        try {
            var url = BuiltinPack.class.getClassLoader().getResource(ROOT);
            return url == null ? null : Path.of(url.toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public @Nullable String read(final String path) {
        return contents().get(path);
    }

    @Override
    public Set<String> files() {
        return Set.copyOf(contents().keySet());
    }

    @Override
    public List<PackOption> options() {
        contents();
        return options == null ? List.of() : options;
    }

    @Override
    public String name() {
        return NAME;
    }

    private static @Nullable Path devDir() {
        String dir = System.getProperty("metallumextra.shaderDir");
        return dir == null || dir.isBlank() ? null : Path.of(dir);
    }
}
