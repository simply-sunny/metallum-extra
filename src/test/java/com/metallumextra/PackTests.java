package com.metallumextra;

import com.metallumextra.shader.pack.BuiltinPack;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ShaderPack;
import com.metallumextra.shader.pack.TranslationCache;
import com.metallumextra.shader.pack.ZipPack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Checks of shader pack loading and the translation cache that need no game: {@code gradlew packTest}. The picture
 * checks that do need the game are in {@code src/test/pack-regression}.
 */
public final class PackTests {
    private static int failures;
    private static int checks;

    private PackTests() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("images")) {
            images(Path.of(args[1]));
            System.out.println(checks + " checks, " + failures + " failed");
            System.exit(failures > 0 ? 1 : 0);
        }
        Path work = Files.createTempDirectory("pack-tests");
        try {
            loading(work.resolve("loading"));
            discovery(work.resolve("discovery"));
            translationCache(work.resolve("cache"));
        } finally {
            try (Stream<Path> walk = Files.walk(work)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // ---- the pictures from src/test/pack-regression/packs.txt ----

    /**
     * Each picture shows a platform that is red on one side and blue on the other. How much of the picture is strongly
     * red and how much strongly blue tells which pack drew it, wherever the camera happens to point; the sky and the
     * lighting are far from either.
     */
    private static void images(final Path folder) throws IOException {
        // name, whether red should be present, whether blue should be present
        Object[][] expected = {
                {"1_builtin", true, true},
                {"2_redtoblue", false, true},
                {"3_bluetored", true, false},
                {"4_builtin_again", true, true},
                {"5_redtoblue_again", false, true}};
        for (Object[] row : expected) {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(folder.resolve(row[0] + ".png").toFile());
            double red = share(image, true);
            double blue = share(image, false);
            boolean ok = ((boolean) row[1] ? red > 0.05 : red < 0.01) && ((boolean) row[2] ? blue > 0.05 : blue < 0.01);
            check(String.format("%s: red %s, blue %s (red %.1f%%, blue %.1f%%)", row[0], (boolean) row[1] ? "present" : "gone",
                    (boolean) row[2] ? "present" : "gone", red * 100, blue * 100), ok);
        }
    }

    /** The share of the picture that is strongly red (or blue): that channel at least 2.5 times each of the others. */
    private static double share(final java.awt.image.BufferedImage image, final boolean red) {
        long hits = 0, total = 0;
        for (int x = 0; x < image.getWidth(); x += 3) {
            for (int y = 0; y < image.getHeight(); y += 3) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                boolean strong = red ? r > 100 && r > g * 2.5 && r > b * 2.5 : b > 100 && b > r * 2.5 && b > g * 2.5;
                if (strong) hits++;
                total++;
            }
        }
        return (double) hits / total;
    }

    // ---- Phase 1 and 2: the format, validation, reading ----

    private static void loading(final Path dir) throws Exception {
        Map<String, String> builtin = TestPacks.builtinFiles();

        // The required list is the shader programs and overrides the jar holds; libraries are not listed.
        Set<String> entryPoints = new TreeSet<>();
        for (String file : builtin.keySet()) {
            if (file.startsWith("program/") || file.startsWith("override/")) entryPoints.add(file);
        }
        check("required list matches the built-in programs and overrides", entryPoints.equals(new TreeSet<>(PackManager.REQUIRED)));

        // The built-in pack is itself a valid pack.
        validates("built-in pack is valid", new BuiltinPack());

        // Reading from the built-in pack and from an identical ZIP gives identical text.
        Path copy = dir.resolve("Copy.zip");
        TestPacks.writeBuiltinCopy(copy);
        ZipPack zip = ZipPack.open(copy);
        check("ZIP name is the file name without .zip", zip.name().equals("Copy"));
        check("program/edges.fsh reads the same from the jar and an identical ZIP", zip.read("program/edges.fsh").equals(new BuiltinPack().read("program/edges.fsh")));
        boolean same = true;
        for (String file : builtin.keySet()) {
            same &= builtin.get(file).equals(zip.read(file));
        }
        check("every file reads the same from the jar and an identical ZIP", same);
        boolean sameLoaded = true;
        for (String file : PackManager.REQUIRED) {
            sameLoaded &= new BuiltinPack().load(file).equals(zip.load(file));
        }
        check("every required shader loads (with includes) the same from the jar and an identical ZIP", sameLoaded);
        validates("identical ZIP is valid", zip);

        // Two packs with a different version of one shader give each their own.
        TestPacks.writeColorPacks(dir);
        ZipPack redToBlue = ZipPack.open(dir.resolve("RedToBlue.zip"));
        ZipPack blueToRed = ZipPack.open(dir.resolve("BlueToRed.zip"));
        check("RedToBlue gives its own edges.fsh", redToBlue.load("program/edges.fsh").contains("vec3(0.0, 0.0, color.r)"));
        check("BlueToRed gives its own edges.fsh", blueToRed.load("program/edges.fsh").contains("vec3(color.b, 0.0, 0.0)"));
        check("the two packs differ only in program/edges.fsh", differOnlyIn(redToBlue, blueToRed, "program/edges.fsh", builtin.keySet()));
        validates("RedToBlue is valid", redToBlue);
        validates("BlueToRed is valid", blueToRed);

        // A required shader removed: rejected, and not borrowed from the built-in pack.
        Map<String, String> missing = TestPacks.builtinFiles();
        missing.remove("program/edges.fsh");
        Path noEdges = dir.resolve("NoEdges.zip");
        TestPacks.write(noEdges, "{\"format\": 1}", missing);
        ZipPack noEdgesPack = ZipPack.open(noEdges);
        check("a pack without program/edges.fsh does not have it", noEdgesPack.read("program/edges.fsh") == null);
        rejects("a pack without a required shader is rejected", noEdgesPack, "program/edges.fsh");

        // An include that is not in the same ZIP: rejected.
        Map<String, String> noLibrary = TestPacks.builtinFiles();
        noLibrary.remove("lib/lighting.glsl");
        Path noLib = dir.resolve("NoLib.zip");
        TestPacks.write(noLib, "{\"format\": 1}", noLibrary);
        rejects("a pack missing a library file its shaders include is rejected", ZipPack.open(noLib), "lighting.glsl");

        // A pack may arrange lib/ as it likes, as long as the includes resolve.
        Map<String, String> renamed = TestPacks.builtinFiles();
        String lighting = renamed.remove("lib/lighting.glsl");
        renamed.put("lib/deeper/folders/lighting.glsl", lighting);
        for (Map.Entry<String, String> file : new java.util.HashMap<>(renamed).entrySet()) {
            renamed.put(file.getKey(), file.getValue().replace("#include \"lighting.glsl\"", "#include \"deeper/folders/lighting.glsl\""));
        }
        Path moved = dir.resolve("Moved.zip");
        TestPacks.write(moved, "{\"format\": 1}", renamed);
        validates("a pack that keeps its library in other folders is valid", ZipPack.open(moved));

        // pack.json.
        expectOpenFails("format 2 is not supported", dir.resolve("Future.zip"), "{\"format\": 2}", "not supported");
        expectOpenFails("pack.json that is not JSON", dir.resolve("Garbage.zip"), "this is not json", "JSON");
        expectOpenFails("pack.json without a format", dir.resolve("NoFormat.zip"), "{}", "format");
        expectOpenFails("pack.json with a text format", dir.resolve("TextFormat.zip"), "{\"format\": \"1\"}", "format");
        validates("extra fields in pack.json are ignored", openWithJson(dir.resolve("Extra.zip"), "{\"format\": 1, \"author\": \"x\"}"));
    }

    private static ZipPack openWithJson(final Path zip, final String json) throws IOException, PackException {
        TestPacks.write(zip, json, TestPacks.builtinFiles());
        return ZipPack.open(zip);
    }

    private static void expectOpenFails(final String what, final Path zip, final String json, final String message) throws IOException {
        TestPacks.write(zip, json, TestPacks.builtinFiles());
        try {
            ZipPack.open(zip);
            check(what, false);
        } catch (PackException e) {
            check(what + " (" + e.getMessage() + ")", e.getMessage().contains(message));
        }
    }

    private static boolean differOnlyIn(final ShaderPack a, final ShaderPack b, final String file, final Set<String> all) {
        for (String path : all) {
            if (path.equals(file)) {
                if (a.read(path).equals(b.read(path))) return false;
            } else if (!a.read(path).equals(b.read(path))) {
                return false;
            }
        }
        return true;
    }

    private static void validates(final String what, final ShaderPack pack) {
        try {
            PackManager.validate(pack);
            check(what, true);
        } catch (PackException e) {
            check(what + " (" + e.getMessage() + ")", false);
        }
    }

    private static void rejects(final String what, final ShaderPack pack, final String mentions) {
        try {
            PackManager.validate(pack);
            check(what, false);
        } catch (PackException e) {
            check(what + " (" + e.getMessage() + ")", e.getMessage().contains(mentions));
        }
    }

    // ---- Phase 3: the folder ----

    private static void discovery(final Path folder) throws Exception {
        TestPacks.writeColorPacks(folder);
        Map<String, String> missing = TestPacks.builtinFiles();
        missing.remove("program/sky.fsh");
        TestPacks.write(folder.resolve("Incomplete.zip"), "{\"format\": 1}", missing);
        // A shader pack for another mod: no pack.json, so it is none of our business.
        TestPacks.write(folder.resolve("SomeIrisPack.zip"), null, Map.of("shaders/final.fsh", "void main() {}"));
        Files.writeString(folder.resolve("notes.txt"), "not a zip");
        // The built-in pack's name, taken by a ZIP.
        TestPacks.writeBuiltinCopy(folder.resolve(BuiltinPack.NAME + ".zip"));
        Files.writeString(folder.resolve("Damaged.zip"), "this is not a zip file");

        List<PackManager.Entry> entries = PackManager.scan(folder);
        List<String> ids = new ArrayList<>();
        for (PackManager.Entry entry : entries) ids.add(entry.id());
        check("built-in pack is always first", ids.get(0).equals(PackManager.BUILTIN_ID));
        check("complete ZIPs are listed", ids.contains("RedToBlue.zip") && ids.contains("BlueToRed.zip"));
        check("a ZIP without pack.json is not listed", !ids.contains("SomeIrisPack.zip"));
        check("an unreadable ZIP is not listed (it is not known to be ours)", !ids.contains("Damaged.zip"));
        check("order is by name", ids.indexOf("BlueToRed.zip") < ids.indexOf("RedToBlue.zip"));

        PackManager.Entry incomplete = entry(entries, "Incomplete.zip");
        check("an incomplete pack is listed with an error and cannot be chosen", incomplete != null && !incomplete.selectable() && incomplete.error().contains("program/sky.fsh"));
        PackManager.Entry clash = entry(entries, BuiltinPack.NAME + ".zip");
        check("a ZIP named like the built-in pack is refused, not silently chosen", clash != null && !clash.selectable() && clash.error().contains("same name"));
        check("the built-in pack is untouched by the clash", entry(entries, PackManager.BUILTIN_ID).selectable());
        check("good packs can be chosen", entry(entries, "RedToBlue.zip").selectable() && entry(entries, "RedToBlue.zip").error() == null);

        // Adding and removing a ZIP shows on the next scan.
        TestPacks.writeBuiltinCopy(folder.resolve("Added.zip"));
        check("a ZIP added later is listed on the next scan", entry(PackManager.scan(folder), "Added.zip") != null);
        Files.delete(folder.resolve("Added.zip"));
        check("a ZIP removed is gone on the next scan", entry(PackManager.scan(folder), "Added.zip") == null);

        // Two ZIPs with the same name (on a case-sensitive disk): both refused.
        Path twin = folder.resolve("redtoblue.zip");
        try {
            TestPacks.writeBuiltinCopy(twin);
            if (Files.list(folder).filter(p -> p.getFileName().toString().equalsIgnoreCase("redtoblue.zip")).count() > 1) {
                List<PackManager.Entry> twins = PackManager.scan(folder);
                check("two ZIPs with the same name are both refused", !entry(twins, "RedToBlue.zip").selectable() && !entry(twins, "redtoblue.zip").selectable());
            } else {
                System.out.println("(skipped: this disk ignores case in file names, so two such ZIPs cannot exist)");
            }
        } finally {
            Files.deleteIfExists(twin);
        }

        // A missing folder is an empty list, not an error.
        List<PackManager.Entry> none = PackManager.scan(folder.resolve("nonexistent"));
        check("a missing shaderpacks folder gives just the built-in pack", none.size() == 1);

        // describe(): the message shown for a pack that broke.
        String described = PackManager.describe(new IllegalStateException("Failed to compile shader a:b", new RuntimeException("edges.fsh:7: error: syntax error\nsecond line")));
        check("a compile error is described with the shader and the compiler's words", described.contains("a:b") && described.contains("syntax error") && !described.contains("\n"));
    }

    private static PackManager.Entry entry(final List<PackManager.Entry> entries, final String id) {
        for (PackManager.Entry entry : entries) {
            if (entry.id().equals(id)) return entry;
        }
        return null;
    }

    // ---- Phase 4: the translation cache ----

    private static void translationCache(final Path folder) throws Exception {
        TranslationCache cache = new TranslationCache(folder);
        ByteBuffer spirv = bytes(1, 2, 3, 4, 5, 6, 7, 8);
        Map<String, String> formats = Map.of("Position", "RGB32_FLOAT", "UV0", "RG32_FLOAT");
        String key = TranslationCache.key("salt", spirv, 3, formats, true);

        check("the same inputs give the same key", key.equals(TranslationCache.key("salt", bytes(1, 2, 3, 4, 5, 6, 7, 8), 3, Map.of("UV0", "RG32_FLOAT", "Position", "RGB32_FLOAT"), true)));
        check("the key does not move the buffer's position", spirv.position() == 0);
        check("key is a SHA-256", key.matches("[0-9a-f]{64}"));
        check("a changed shader changes the key", !key.equals(TranslationCache.key("salt", bytes(1, 2, 3, 4, 5, 6, 7, 9), 3, formats, true)));
        check("a changed resource binding changes the key", !key.equals(TranslationCache.key("salt", spirv, 4, formats, true)));
        check("a changed vertex input format changes the key", !key.equals(TranslationCache.key("salt", spirv, 3, Map.of("Position", "RGB32_FLOAT", "UV0", "RG16_FLOAT"), true)));
        check("a changed option changes the key", !key.equals(TranslationCache.key("salt", spirv, 3, formats, false)));
        check("a changed compiler or Metallum version changes the key", !key.equals(TranslationCache.key("salt2", spirv, 3, formats, true)));

        TranslationCache.Msl msl = new TranslationCache.Msl("#include <metal_stdlib>\nvertex float4 main0() {\n    return float4(0); // é中\n}\n", true, Set.of("Projection", "Sampler0"));
        int hits = TranslationCache.hits();
        int translations = TranslationCache.translations();

        check("a new key is a miss", cache.lookup(key) == null);
        check("a miss is not counted as a hit", TranslationCache.hits() == hits);
        cache.store(key, msl);
        check("a stored conversion is counted as a translation", TranslationCache.translations() == translations + 1);
        TranslationCache.Msl back = cache.lookup(key);
        check("a stored entry is found again, text and all", back != null && back.source().equals(msl.source()));
        check("the metadata comes back too", back != null && back.hasPushConstants() && back.activeResources().equals(msl.activeResources()));
        check("a hit is counted", TranslationCache.hits() == hits + 1);
        check("the entry is a <hash>.msl file", Files.isRegularFile(folder.resolve(key + ".msl")));
        check("no temporary files are left behind", noTemporaryFiles(folder));

        TranslationCache.Msl plain = new TranslationCache.Msl("fragment float4 main0() { return 0; }\n", false, Set.of());
        String plainKey = TranslationCache.key("salt", bytes(9, 9), 0, Map.of(), false);
        cache.store(plainKey, plain);
        TranslationCache.Msl plainBack = cache.lookup(plainKey);
        check("an entry without push constants or resources comes back as it was", plainBack != null && !plainBack.hasPushConstants() && plainBack.activeResources().isEmpty() && plainBack.source().equals(plain.source()));

        // A damaged entry is ignored (and removed), and the shader is converted again.
        Path file = folder.resolve(key + ".msl");
        String text = Files.readString(file);
        Files.writeString(file, text.substring(0, text.length() - 10));
        check("a truncated entry is a miss", cache.lookup(key) == null);
        check("a damaged entry is removed", !Files.exists(file));
        cache.store(key, msl);
        Files.writeString(file, text.replace("main0", "mainX"));
        check("an entry whose text was changed is a miss", cache.lookup(key) == null);
        Files.writeString(file, "");
        check("an empty entry is a miss", cache.lookup(key) == null);
        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1, 2});
        check("an entry of garbage bytes is a miss", cache.lookup(key) == null);

        // The folder deleted: everything still works.
        cache.store(key, msl);
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        check("with the folder deleted, a lookup is a miss", cache.lookup(key) == null);
        cache.store(key, msl);
        check("with the folder deleted, the next store makes it again", cache.lookup(key) != null);

        // A folder that cannot be written to does no harm.
        TranslationCache blocked = new TranslationCache(folder.resolve(key + ".msl").resolve("inside"));
        blocked.store("abc", msl);
        check("a cache that cannot be written to still counts and does not throw", blocked.lookup("abc") == null);

        // A new cache object over the same folder (a restart) finds what was stored.
        check("a new cache over the same folder (a restart) finds the entry", new TranslationCache(folder).lookup(key) != null);
        check("the counter reads as the debug line", TranslationCache.summary().matches("MSL cache hits: \\d+, MSL translations: \\d+"));
    }

    private static boolean noTemporaryFiles(final Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        }
    }

    private static ByteBuffer bytes(final int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return ByteBuffer.wrap(bytes);
    }

    private static void check(final String what, final boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }
}
