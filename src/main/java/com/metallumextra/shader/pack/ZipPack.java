package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A shader pack in an ordinary ZIP file, in the Iris layout: a {@code shaders/} folder. The whole folder is read into memory when the pack
 * is opened, so the pack cannot change under a shader that is being compiled, and the file can be replaced on disk while it is in use.
 */
public final class ZipPack implements ShaderPack {
    private static final String SHADERS = "shaders/";
    /** A pack is a few hundred kilobytes of text; this only stops a damaged or hostile file from filling memory. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private final String name;
    private final Map<String, String> files;
    private final List<PackOption> options;

    private ZipPack(final String name, final Map<String, String> files, final List<PackOption> options) {
        this.name = name;
        this.files = files;
        this.options = options;
    }

    /**
     * Whether the ZIP is a shader pack at all: it has a {@code shaders/} folder holding shaders or {@code shaders.properties}, or it has
     * the {@code pack.json} of this mod's earlier layout (which is listed, with the reason it cannot be used). Other ZIPs are not ours to judge.
     */
    public static boolean isPack(final Path zip) {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            if (file.getEntry("pack.json") != null) return true;
            for (var entries = file.entries(); entries.hasMoreElements(); ) {
                String entry = entries.nextElement().getName();
                if (entry.startsWith(SHADERS) && (entry.endsWith(".fsh") || entry.endsWith(".vsh") || entry.endsWith("/shaders.properties"))) return true;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** @throws PackException if the ZIP cannot be read or is a pack in this mod's earlier layout */
    public static ZipPack open(final Path zip) throws PackException {
        String fileName = zip.getFileName().toString();
        String name = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            if (file.getEntry("pack.json") != null) {
                throw new PackException("It is in this mod's earlier pack layout (pack.json, program/, override/), which is no longer read. Packs now use the Iris layout: see docs/shader-packs.md");
            }
            Map<String, String> files = new HashMap<>();
            long budget = MAX_BYTES;
            for (var entries = file.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.startsWith(SHADERS)) continue;
                byte[] bytes = read(file, entry, budget);
                budget -= bytes.length;
                files.put(entryName.substring(SHADERS.length()), new String(bytes, StandardCharsets.UTF_8));
            }
            if (files.isEmpty()) throw new PackException("there is no shaders/ folder with files in it");
            return new ZipPack(name, Map.copyOf(files), StandardOptions.discover(files));
        } catch (IOException | RuntimeException e) {
            if (e instanceof PackException pack) throw pack;
            throw new PackException("could not read the ZIP: " + e.getMessage(), e);
        }
    }

    private static byte[] read(final ZipFile file, final ZipEntry entry, final long limit) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
            if (bytes.length > limit) throw new IOException(entry.getName() + " is too large");
            return bytes;
        }
    }

    @Override
    public @Nullable String read(final String path) {
        return files.get(path);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> files() {
        return files.keySet();
    }

    @Override
    public List<PackOption> options() {
        return options;
    }
}
