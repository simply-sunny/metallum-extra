package com.metallumextra.shader.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A shader pack in an ordinary ZIP file: {@code pack.json} and a {@code shaders/} folder. The whole folder is read
 * into memory when the pack is opened, so the pack cannot change under a shader that is being compiled, and the file
 * can be replaced on disk while it is in use.
 */
public final class ZipPack implements ShaderPack {
    public static final int FORMAT = 1;
    private static final String SHADERS = "shaders/";
    /** A pack is a few hundred kilobytes of text; this only stops a damaged or hostile file from filling memory. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final int MAX_OPTIONS = 64;

    private final String name;
    private final Map<String, String> files;
    private final List<PackOption> options;
    private final boolean standard;

    private ZipPack(final String name, final Map<String, String> files, final List<PackOption> options, final boolean standard) {
        this.name = name;
        this.files = files;
        this.options = options;
        this.standard = standard;
    }

    /**
     * Whether the ZIP is a shader pack at all: it has a {@code pack.json} (this mod's earlier layout) or a
     * {@code shaders/} folder holding shaders or {@code shaders.properties} (the Iris layout). Other ZIPs are not ours to judge.
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

    /** @throws PackException if the ZIP cannot be read or its {@code pack.json} is not one this mod understands */
    public static ZipPack open(final Path zip) throws PackException {
        String fileName = zip.getFileName().toString();
        String name = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry meta = file.getEntry("pack.json");
            // No pack.json: the Iris layout, which has no metadata file of ours.
            boolean standard = meta == null;
            List<PackOption> options = standard ? List.of() : parse(new String(read(file, meta, MAX_BYTES), StandardCharsets.UTF_8));

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
            if (standard && files.isEmpty()) throw new PackException("there is no shaders/ folder with files in it");
            return new ZipPack(name, Map.copyOf(files), options, standard);
        } catch (IOException | RuntimeException e) {
            if (e instanceof PackException pack) throw pack;
            throw new PackException("could not read the ZIP: " + e.getMessage(), e);
        }
    }

    private static List<PackOption> parse(final String json) throws PackException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new PackException("pack.json is not valid JSON", e);
        }
        if (!root.has("format") || !root.get("format").isJsonPrimitive() || !root.get("format").getAsJsonPrimitive().isNumber()) {
            throw new PackException("pack.json has no \"format\" number");
        }
        int format = root.get("format").getAsInt();
        if (format != FORMAT) {
            throw new PackException("pack format " + format + " is not supported (this version reads format " + FORMAT + ")");
        }
        return parseOptions(root.get("options"));
    }

    private static List<PackOption> parseOptions(final JsonElement element) throws PackException {
        if (element == null) return List.of();
        if (!element.isJsonArray()) throw new PackException("pack.json: \"options\" must be a list");
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_OPTIONS) throw new PackException("pack.json: more than " + MAX_OPTIONS + " options");
        List<PackOption> options = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String where = "pack.json: option " + (i + 1);
            if (!array.get(i).isJsonObject()) throw new PackException(where + " must be an object");
            JsonObject o = array.get(i).getAsJsonObject();
            String id = text(o, "id", where);
            if (!PackOption.ID.matcher(id).matches()) {
                throw new PackException(where + ": id \"" + id + "\" must be capital letters, digits and _, starting with a letter");
            }
            if (!ids.add(id)) throw new PackException(where + ": id " + id + " is used twice");
            where = "pack.json: option " + id;
            String label = o.has("name") ? text(o, "name", where) : id;
            String type = o.has("type") ? text(o, "type", where) : "choice";
            if (type.equals("toggle")) {
                boolean on = false;
                if (o.has("default")) {
                    if (!o.get("default").isJsonPrimitive() || !o.get("default").getAsJsonPrimitive().isBoolean()) {
                        throw new PackException(where + ": default of a toggle must be true or false");
                    }
                    on = o.get("default").getAsBoolean();
                }
                options.add(new PackOption(id, label, List.of("Off", "On"), on ? 1 : 0, true));
            } else if (type.equals("choice")) {
                if (!o.has("values") || !o.get("values").isJsonArray()) throw new PackException(where + ": \"values\" must be a list");
                List<String> values = new ArrayList<>();
                for (JsonElement v : o.getAsJsonArray("values")) {
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().isBlank()) {
                        throw new PackException(where + ": every value must be a text");
                    }
                    if (values.contains(v.getAsString())) throw new PackException(where + ": value " + v.getAsString() + " is listed twice");
                    values.add(v.getAsString());
                }
                if (values.size() < 2 || values.size() > PackOption.MAX_VALUES) {
                    throw new PackException(where + ": needs 2 to " + PackOption.MAX_VALUES + " values");
                }
                int def = 0;
                if (o.has("default")) {
                    def = values.indexOf(text(o, "default", where));
                    if (def < 0) throw new PackException(where + ": default is not one of the values");
                }
                options.add(new PackOption(id, label, List.copyOf(values), def, false));
            } else {
                throw new PackException(where + ": type must be \"choice\" or \"toggle\"");
            }
        }
        return List.copyOf(options);
    }

    private static String text(final JsonObject o, final String key, final String where) throws PackException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
            throw new PackException(where + ": \"" + key + "\" must be a text");
        }
        return e.getAsString();
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
    public boolean standard() {
        return standard;
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
