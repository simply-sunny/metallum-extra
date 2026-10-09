package com.metallumextra;

import com.metallumextra.shader.pack.BuiltinPack;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.PackOption;
import com.metallumextra.shader.pack.PackOptions;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.metallumextra.shader.pack.TranslationCache;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        if (args.length == 2 && args[0].equals("iris")) {
            iris(Path.of(args[1]));
            System.out.println(checks + " checks, " + failures + " failed");
            System.exit(failures > 0 ? 1 : 0);
        }
        if (args.length >= 3 && args[0].equals("compare")) {
            compare(Path.of(args[1]), Path.of(args[2]), args.length > 3 ? Double.parseDouble(args[3]) : 0.5, args.length > 4 ? Double.parseDouble(args[4]) : 1.0);
            System.out.println(checks + " checks, " + failures + " failed");
            System.exit(failures > 0 ? 1 : 0);
        }
        Path work = Files.createTempDirectory("pack-tests");
        try {
            System.setProperty("metallumextra.packOptionsFile", work.resolve("packs.properties").toString());
            loading(work.resolve("loading"));
            options(work.resolve("options"));
            standardLayout(work.resolve("standard"));
            irisPlans(work.resolve("plans"));
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

    // ---- the multi-pass pipeline test: src/test/iris-pipeline/run.sh ----

    /** A program the pipeline ran: the frame, its stage and name, and the buffers it wrote. */
    private record Run(int frame, String stage, String program, String writes) {
    }

    /** What a program drew into a buffer: five pixels read back, as r, g, b, a. */
    private record Pixels(int frame, String program, int buffer, int width, int height, int[][] pixel) {
    }

    private static final Pattern RUN_LINE = Pattern.compile("iris frame (\\d+) stage (\\w+) program (\\w+) writes (\\[[\\d, ]*\\])");
    private static final Pattern PIXEL_LINE = Pattern.compile("iris frame (\\d+) program (\\w+) colortex(\\d+) (\\d+)x(\\d+) pixels(.*)");
    private static final Pattern PIXEL = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+)\\)");

    /** Everything the log said while one pack was the one asked for: from its {@code pack} step to the next. */
    private static final class Section {
        final String pack;
        final List<Run> runs = new ArrayList<>();
        final List<Pixels> pixels = new ArrayList<>();

        Section(final String pack) {
            this.pack = pack;
        }

        List<String> order(final int frame) {
            List<String> result = new ArrayList<>();
            for (Run run : runs) if (run.frame == frame) result.add(run.program);
            return result;
        }

        List<Integer> frames() {
            List<Integer> result = new ArrayList<>();
            for (Run run : runs) if (!result.contains(run.frame)) result.add(run.frame);
            return result;
        }

        /** What {@code program} drew into {@code buffer}, frame by frame. */
        List<Pixels> drew(final String program, final int buffer) {
            List<Pixels> result = new ArrayList<>();
            for (Pixels p : pixels) if (p.program.equals(program) && p.buffer == buffer) result.add(p);
            result.sort(java.util.Comparator.comparingInt(Pixels::frame));
            return result;
        }
    }

    private static boolean near(final int[] pixel, final int red, final int green, final int blue) {
        return Math.abs(pixel[0] - red) <= 3 && Math.abs(pixel[1] - green) <= 3 && Math.abs(pixel[2] - blue) <= 3;
    }

    /** Every pixel sampled of what {@code program} drew into {@code buffer} (in every frame traced) is this color. */
    private static void drewColor(final Section section, final String program, final int buffer, final int red, final int green, final int blue) {
        List<Pixels> list = section.drew(program, buffer);
        boolean ok = !list.isEmpty();
        StringBuilder seen = new StringBuilder();
        for (Pixels p : list) {
            for (int[] pixel : p.pixel) {
                ok &= near(pixel, red, green, blue);
                if (seen.length() < 60) seen.append(java.util.Arrays.toString(pixel));
            }
        }
        check(String.format("%s: %s drew colortex%d (%d, %d, %d) everywhere sampled in %d frames%s", section.pack, program, buffer, red, green, blue, list.size(), ok ? "" : " (saw " + seen + ")"), ok);
    }

    private static double share(final java.awt.image.BufferedImage image, final int red, final int green, final int blue, final double from, final double to) {
        long hits = 0, total = 0;
        for (int x = (int) (image.getWidth() * from); x < (int) (image.getWidth() * to); x += 2) {
            for (int y = 0; y < image.getHeight(); y += 2) {
                int rgb = image.getRGB(x, y);
                if (Math.abs(((rgb >> 16) & 255) - red) <= 4 && Math.abs(((rgb >> 8) & 255) - green) <= 4 && Math.abs((rgb & 255) - blue) <= 4) hits++;
                total++;
            }
        }
        return hits / (double) total;
    }

    private static java.awt.image.BufferedImage picture(final Path folder, final String name) throws IOException {
        return javax.imageio.ImageIO.read(folder.resolve(name + ".png").toFile());
    }

    private static void screenIs(final Path folder, final String name, final int red, final int green, final int blue, final double least) throws IOException {
        double share = share(picture(folder, name), red, green, blue, 0, 1);
        check(String.format("%s: the screen is (%d, %d, %d) over at least %.0f%% (%.1f%%)", name, red, green, blue, least * 100, share * 100), share >= least);
    }

    private static void iris(final Path folder) throws IOException {
        Map<String, Section> sections = new java.util.LinkedHashMap<>();
        Section current = new Section("start");
        List<String> windows = new ArrayList<>();
        List<String> lines = Files.readAllLines(folder.resolve("game.log"));
        for (String line : lines) {
            int pack = line.indexOf("debug script: pack ");
            if (pack >= 0) {
                String name = line.substring(pack + "debug script: pack ".length()).trim().replace(".zip", "");
                current = sections.computeIfAbsent(name, Section::new);
                continue;
            }
            Matcher run = RUN_LINE.matcher(line);
            if (run.find()) {
                current.runs.add(new Run(Integer.parseInt(run.group(1)), run.group(2), run.group(3), run.group(4)));
                continue;
            }
            Matcher pixels = PIXEL_LINE.matcher(line);
            if (pixels.find()) {
                List<int[]> points = new ArrayList<>();
                Matcher point = PIXEL.matcher(pixels.group(6));
                while (point.find()) points.add(new int[] {Integer.parseInt(point.group(1)), Integer.parseInt(point.group(2)), Integer.parseInt(point.group(3)), Integer.parseInt(point.group(4))});
                current.pixels.add(new Pixels(Integer.parseInt(pixels.group(1)), pixels.group(2), Integer.parseInt(pixels.group(3)), Integer.parseInt(pixels.group(4)), Integer.parseInt(pixels.group(5)),
                        points.toArray(new int[0][])));
                continue;
            }
            if (line.contains("debug script: window is now ")) windows.add(line.substring(line.indexOf("window is now ") + "window is now ".length()).trim() + "@" + (current.pack));
        }
        Section none = new Section("none");
        java.util.function.Function<String, Section> get = name -> sections.getOrDefault(name, none);

        // A1: one buffer.
        Section single = get.apply("IrisSingle");
        drewColor(single, "composite", 0, 255, 0, 0);
        check("IrisSingle: the programs ran composite then final", single.order(single.frames().get(0)).equals(List.of("composite", "final")));
        screenIs(folder, "IrisSingle", 255, 0, 0, 0.99);

        // A2: two buffers, each drawn once.
        Section two = get.apply("IrisTwoTargets");
        drewColor(two, "composite", 0, 255, 0, 0);
        drewColor(two, "composite", 1, 0, 0, 255);
        check("IrisTwoTargets: left half red, right half blue", share(picture(folder, "IrisTwoTargets"), 255, 0, 0, 0, 0.49) > 0.98 && share(picture(folder, "IrisTwoTargets"), 0, 0, 255, 0.51, 1) > 0.98);

        // A4: a program that draws one buffer leaves the other as it was.
        Section independent = get.apply("IrisIndependent");
        drewColor(independent, "composite1", 0, 0, 255, 0);
        check("IrisIndependent: composite1 drew only colortex0", independent.drew("composite1", 1).isEmpty() && independent.runs.stream().anyMatch(r -> r.program.equals("composite1") && r.writes.equals("[0]")));
        check("IrisIndependent: left half green, right half still blue", share(picture(folder, "IrisIndependent"), 0, 255, 0, 0, 0.49) > 0.98 && share(picture(folder, "IrisIndependent"), 0, 0, 255, 0.51, 1) > 0.98);

        // A3: flipping.
        Section once = get.apply("IrisFlipOnce");
        drewColor(once, "composite", 0, 255, 0, 0);
        drewColor(once, "composite1", 0, 0, 0, 255);
        screenIs(folder, "IrisFlipOnce", 0, 0, 255, 0.99);
        Section twice = get.apply("IrisFlipTwice");
        drewColor(twice, "composite", 0, 255, 0, 0);
        drewColor(twice, "composite1", 0, 0, 255, 0);
        drewColor(twice, "composite2", 0, 0, 0, 255);
        screenIs(folder, "IrisFlipTwice", 0, 0, 255, 0.99);
        Section noFlip = get.apply("IrisNoFlip");
        drewColor(noFlip, "composite", 0, 255, 0, 0);
        drewColor(noFlip, "composite1", 0, 0, 0, 255);
        screenIs(folder, "IrisNoFlip", 0, 0, 255, 0.99);

        // A5: a buffer that is not cleared keeps its value from one frame to the next; one that is cleared does not.
        Section persist = get.apply("IrisPersist");
        List<Pixels> kept = persist.drew("composite", 1);
        List<Pixels> cleared = persist.drew("composite", 2);
        boolean counts = kept.size() >= 3;
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            values.append(kept.get(i).pixel[0][0]).append(' ');
            if (i > 0) counts &= Math.floorMod(kept.get(i).pixel[0][0] - kept.get(i - 1).pixel[0][0], 256) == 16 && kept.get(i).frame - kept.get(i - 1).frame == 1;
        }
        check("IrisPersist: colortex1 (never cleared) counts up by 16 every frame, wrapping at 256: " + values.toString().trim(), counts);
        boolean constant = cleared.size() >= 3;
        for (Pixels p : cleared) constant &= p.pixel[0][0] == 16;
        check("IrisPersist: colortex2 (cleared every frame) stays at 16", constant);

        // B1: numbered programs run by number.
        Section order = get.apply("IrisOrder");
        check("IrisOrder: composite, composite2, composite10, final in that order, every frame", !order.frames().isEmpty() && order.frames().stream().allMatch(f -> order.order(f).equals(List.of("composite", "composite2", "composite10", "final"))));
        screenIs(folder, "IrisOrder", 0, 0, 255, 0.99);

        // B2: a program switched off does not run, and the next one gets the right input.
        Section disabled = get.apply("IrisDisabled");
        check("IrisDisabled: composite1 never ran", !disabled.frames().isEmpty() && disabled.frames().stream().allMatch(f -> disabled.order(f).equals(List.of("composite", "composite2", "final"))));
        drewColor(disabled, "composite2", 0, 0, 0, 255);
        screenIs(folder, "IrisDisabled", 0, 0, 255, 0.99);

        // B3: no final program: the screen shows buffer 0.
        Section noFinal = get.apply("IrisNoFinal");
        check("IrisNoFinal: only composite and composite2 ran", !noFinal.frames().isEmpty() && noFinal.frames().stream().allMatch(f -> noFinal.order(f).equals(List.of("composite", "composite2"))));
        screenIs(folder, "IrisNoFinal", 0, 0, 255, 0.99);

        // A8: the three depth textures. depthtex0 and depthtex1 differ where translucent terrain wrote depth (the glass block in
        // the middle of the picture) and nowhere else; depthtex2 is depthtex1 (the hand is drawn after everything here).
        java.awt.image.BufferedImage depthPicture = picture(folder, "IrisDepth");
        long insideDiffers = 0, insideTotal = 0, outsideDiffers = 0, greenAnywhere = 0;
        for (int x = 0; x < depthPicture.getWidth(); x += 2) {
            for (int y = 0; y < depthPicture.getHeight(); y += 2) {
                int rgb = depthPicture.getRGB(x, y);
                boolean differs = ((rgb >> 16) & 255) > 128;
                if ((rgb >> 8 & 255) > 128) greenAnywhere++;
                boolean inGlass = x > depthPicture.getWidth() * 0.37 && x < depthPicture.getWidth() * 0.63 && y > depthPicture.getHeight() * 0.33 && y < depthPicture.getHeight() * 0.77;
                boolean clearOfGlass = x < depthPicture.getWidth() * 0.3 || x > depthPicture.getWidth() * 0.7 || y < depthPicture.getHeight() * 0.25 || y > depthPicture.getHeight() * 0.85;
                if (inGlass) {
                    insideTotal++;
                    if (differs) insideDiffers++;
                }
                if (clearOfGlass && differs) outsideDiffers++;
            }
        }
        check(String.format("IrisDepth: depthtex0 differs from depthtex1 over %.1f%% of the glass block and nowhere else (%d pixels outside)", 100.0 * insideDiffers / insideTotal, outsideDiffers),
                insideDiffers > insideTotal / 50 && outsideDiffers == 0);
        check("IrisDepth: depthtex2 is the same as depthtex1 everywhere (" + greenAnywhere + " pixels differ)", greenAnywhere == 0);

        // Stages: where in the frame each runs, and the deferred program's result reaching the screen.
        Section stages = get.apply("IrisStages");
        check("IrisStages: begin, prepare, deferred, final in that order, every frame", !stages.frames().isEmpty() && stages.frames().stream().allMatch(f -> stages.order(f).equals(List.of("begin", "prepare", "deferred", "final"))));
        drewColor(stages, "begin", 2, 255, 0, 0);
        drewColor(stages, "prepare", 2, 255, 255, 0);
        drewColor(stages, "deferred", 0, 0, 255, 0);
        screenIs(folder, "IrisStages", 0, 255, 0, 0.9);

        // A pack that does not compile is put aside; the one before it takes over.
        check("IrisBroken: it was reported broken and put aside", lines.stream().anyMatch(l -> l.contains("IrisBroken.zip is broken")));
        screenIs(folder, "IrisBroken_after", 0, 255, 0, 0.9);

        // A6: the buffers follow the window.
        List<String> sizes = new ArrayList<>();
        for (String window : windows) sizes.add(window.substring(0, window.indexOf('@')));
        Section resized = get.apply("IrisFlipTwice");
        check("resize: after the window became " + sizes + " the buffers had those sizes (seen " + sizesOf(resized) + ")", sizes.size() >= 2 && sizesOf(resized).containsAll(sizes));

        // A7: a pack never sees another pack's buffers.
        screenIs(folder, "IrisReadsOne_after_TwoTargets", 255, 255, 255, 0.99);

        // Switching shaders off and on, reloading, other dimensions.
        check("shaders off: the screen is not the pack's red", share(picture(folder, "shaders_off"), 255, 0, 0, 0, 1) < 0.05);
        screenIs(folder, "shaders_on_again", 255, 0, 0, 0.99);
        screenIs(folder, "after_reload", 255, 0, 0, 0.99);
        check("the Nether has no program in this pack, so it is not red", share(picture(folder, "nether_view"), 255, 0, 0, 0, 1) < 0.05);
        screenIs(folder, "overworld_again", 255, 0, 0, 0.99);
        check("built-in shaders again after the packs: not one flat color", share(picture(folder, "builtin_after"), 255, 0, 0, 0, 1) < 0.05 && share(picture(folder, "builtin_after"), 0, 255, 0, 0, 1) < 0.05);

        // Metal's validation layer, and errors we did not expect.
        List<String> validation = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if ((lower.contains("validation") && !lower.contains("library validation") && !lower.contains("validation enabled")) || lower.contains("mtldebug") || lower.contains("failed assertion")) validation.add(line);
            if (line.contains("Render thread/ERROR") && !line.contains("IrisBroken")) errors.add(line);
        }
        check("no Metal validation messages in the log" + (validation.isEmpty() ? "" : ": " + validation.get(0)), validation.isEmpty());
        check("no errors in the log except the broken pack's" + (errors.isEmpty() ? "" : ": " + errors.get(0)), errors.isEmpty());
    }

    private static List<String> sizesOf(final Section section) {
        List<String> result = new ArrayList<>();
        for (Pixels p : section.pixels) {
            String size = p.width + "x" + p.height;
            if (!result.contains(size)) result.add(size);
        }
        return result;
    }

    // ---- the pictures of two runs of src/test/baseline/run.sh ----

    /**
     * Every picture in {@code a} must have a twin in {@code b} that shows the same scene (the {@code .txt} next to it says
     * where and with what it was taken) and differs by at most {@code meanTolerance} (the average difference per
     * color channel, 0 to 255) and in at most {@code percentTolerance} percent of its pixels by more than 30 (summed over the channels).
     */
    private static void compare(final Path a, final Path b, final double meanTolerance, final double percentTolerance) throws IOException {
        try (Stream<Path> list = Files.list(a)) {
            for (Path first : list.filter(p -> p.toString().endsWith(".png")).sorted().toList()) {
                String name = first.getFileName().toString();
                Path second = b.resolve(name);
                if (!Files.exists(second)) {
                    check(name + ": missing from " + b, false);
                    continue;
                }
                String infoA = Files.readString(first.resolveSibling(name.replace(".png", ".txt")));
                String infoB = Files.readString(second.resolveSibling(name.replace(".png", ".txt")));
                // The game time of a scene differs between runs; everything else about it must not.
                check(name + ": same scene (resolution, place, dimension, settings)", sceneOf(infoA).equals(sceneOf(infoB)));
                java.awt.image.BufferedImage x = javax.imageio.ImageIO.read(first.toFile());
                java.awt.image.BufferedImage y = javax.imageio.ImageIO.read(second.toFile());
                if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) {
                    check(name + ": same size", false);
                    continue;
                }
                long sum = 0, strong = 0, total = 0;
                for (int i = 0; i < x.getWidth(); i++) {
                    for (int j = 0; j < x.getHeight(); j++) {
                        int p = x.getRGB(i, j), q = y.getRGB(i, j);
                        int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255));
                        sum += d;
                        if (d > 30) strong++;
                        total++;
                    }
                }
                double mean = sum / (double) total / 3.0;
                double percent = 100.0 * strong / total;
                check(String.format("%s: mean difference %.3f (limit %.2f), %.3f%% of pixels differ strongly (limit %.2f%%)", name, mean, meanTolerance, percent, percentTolerance),
                        mean <= meanTolerance && percent <= percentTolerance);
            }
        }
    }

    /** The lines of a scene description that must agree between runs: all but the game time. */
    private static String sceneOf(final String info) {
        return info.lines().filter(line -> !line.startsWith("gameTime")).collect(java.util.stream.Collectors.joining("\n"));
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
                {"5_redtoblue_again", false, true},
                {"9_solidblue", false, true},
                {"10_solidred", true, false},
                {"11_swap", true, true},
                {"12_builtin_after_standard", true, true},
                {"6_option_off", true, true},
                {"7_option_on", false, true},
                {"8_option_off_again", true, true}};
        for (Object[] row : expected) {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(folder.resolve(row[0] + ".png").toFile());
            double red = share(image, true);
            double blue = share(image, false);
            boolean ok = ((boolean) row[1] ? red > 0.05 : red < 0.01) && ((boolean) row[2] ? blue > 0.05 : blue < 0.01);
            check(String.format("%s: red %s, blue %s (red %.1f%%, blue %.1f%%)", row[0], (boolean) row[1] ? "present" : "gone",
                    (boolean) row[2] ? "present" : "gone", red * 100, blue * 100), ok);
        }
        imageRelations(folder);
    }

    private static void imageRelations(final Path folder) throws IOException {
        // The built-in pack draws more red than blue here; swapping the two in a standard pack's final program reverses that.
        java.awt.image.BufferedImage plain = javax.imageio.ImageIO.read(folder.resolve("12_builtin_after_standard.png").toFile());
        java.awt.image.BufferedImage swapped = javax.imageio.ImageIO.read(folder.resolve("11_swap.png").toFile());
        check("a standard pack that swaps red and blue shows more blue than red where the built-in pack shows more red",
                share(plain, true) > share(plain, false) && share(swapped, false) > share(swapped, true));
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

    private static final String OPTIONS_JSON = "{\"format\": 1, \"options\": ["
            + "{\"id\": \"QUALITY\", \"name\": \"Quality\", \"values\": [\"Low\", \"Medium\", \"High\", \"Ultra\"], \"default\": \"High\"},"
            + "{\"id\": \"BLOOM\", \"type\": \"toggle\", \"default\": true},"
            + "{\"id\": \"GRAIN\", \"type\": \"toggle\"}]}";

    private static void options(final Path dir) throws Exception {
        ZipPack pack = openWithJson(dir.resolve("WithOptions.zip"), OPTIONS_JSON);
        var options = pack.options();
        check("three options are read", options.size() == 3);
        check("a choice keeps its values and default", options.get(0).values().equals(java.util.List.of("Low", "Medium", "High", "Ultra")) && options.get(0).defaultIndex() == 2);
        check("a toggle is Off/On and defaults on when asked", options.get(1).toggle() && options.get(1).defaultIndex() == 1 && options.get(1).values().equals(java.util.List.of("Off", "On")));
        check("a toggle is off by default otherwise", options.get(2).defaultIndex() == 0 && options.get(2).label().equals("GRAIN"));
        check("a pack without options has none", openWithJson(dir.resolve("Plain.zip"), "{\"format\": 1}").options().isEmpty());
        validates("a pack with options is valid", pack);

        String edges = pack.load("program/edges.fsh");
        String[] lines = edges.split("\n");
        check("defines come right after the #version line", lines[0].startsWith("#version") && lines[1].equals("#define OPTION_QUALITY 2") && lines[2].equals("#define OPTION_BLOOM 1") && lines[3].equals("#define OPTION_GRAIN 0"));
        check("a pack without options gets no defines", !openWithJson(dir.resolve("Plain.zip"), "{\"format\": 1}").load("program/edges.fsh").contains("OPTION_"));

        PackOptions.set("WithOptions", options.get(0), 3);
        PackOptions.set("WithOptions", options.get(2), 1);
        String changed = pack.load("program/edges.fsh");
        check("a chosen value reaches the shader", changed.contains("#define OPTION_QUALITY 3\n") && changed.contains("#define OPTION_GRAIN 1\n"));
        check("options are kept per pack", PackOptions.get("Other", options.get(0)) == 2);
        check("the choice is saved by label, so a reordered pack keeps it", options.get(0).indexOf("Ultra") == 3 && new PackOption("QUALITY", "Quality", java.util.List.of("Ultra", "Low"), 1, false).indexOf("Ultra") == 0);
        check("a value the pack no longer has falls back to the default", new PackOption("QUALITY", "Quality", java.util.List.of("Low", "High"), 1, false).indexOf("Ultra") == 1);
        PackOptions.reset("WithOptions", options);
        check("reset brings the defaults back", pack.load("program/edges.fsh").contains("#define OPTION_QUALITY 2\n"));

        expectOpenFails("an option id in lower case is refused", dir.resolve("B1.zip"), "{\"format\":1,\"options\":[{\"id\":\"quality\",\"type\":\"toggle\"}]}", "id");
        expectOpenFails("a repeated id is refused", dir.resolve("B2.zip"), "{\"format\":1,\"options\":[{\"id\":\"A\",\"type\":\"toggle\"},{\"id\":\"A\",\"type\":\"toggle\"}]}", "twice");
        expectOpenFails("a choice with one value is refused", dir.resolve("B3.zip"), "{\"format\":1,\"options\":[{\"id\":\"A\",\"values\":[\"x\"]}]}", "values");
        expectOpenFails("a default that is not a value is refused", dir.resolve("B4.zip"), "{\"format\":1,\"options\":[{\"id\":\"A\",\"values\":[\"x\",\"y\"],\"default\":\"z\"}]}", "default");
        expectOpenFails("an unknown type is refused", dir.resolve("B5.zip"), "{\"format\":1,\"options\":[{\"id\":\"A\",\"type\":\"slider\"}]}", "type");
        expectOpenFails("options that are not a list are refused", dir.resolve("B6.zip"), "{\"format\":1,\"options\":{}}", "list");
    }

    private static ZipPack openStandard(final Path zip, final Map<String, String> files) throws IOException, PackException {
        TestPacks.writeStandard(zip, files);
        return ZipPack.open(zip);
    }

    private static Map<String, String> finalFiles() {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("world0/final.vsh", "#version 330\nvoid main() { gl_Position = vec4(0.0); }\n");
        files.put("world0/final.fsh", TestPacks.finalFragment("fragColor = color;"));
        return files;
    }

    private static void standardLayout(final Path dir) throws Exception {
        Files.createDirectories(dir);
        TestPacks.writeStandardPack(dir.resolve("SolidBlue.zip"), "fragColor = vec4(0.0, 0.0, 1.0, 1.0);");
        check("a ZIP with shaders/ and no pack.json is a pack", ZipPack.isPack(dir.resolve("SolidBlue.zip")));
        ZipPack blue = ZipPack.open(dir.resolve("SolidBlue.zip"));
        check("it is a standard pack, named after the file", blue.standard() && blue.name().equals("SolidBlue"));
        check("an older pack is not a standard one", !openWithJson(dir.resolve("Old.zip"), "{\"format\": 1}").standard());
        validates("a pack with only world0/final is valid", blue);

        ProgramSet overworld = ProgramSet.discover(blue, "world0");
        check("final is found in the overworld folder", overworld.find("final") != null && overworld.find("final").fragment().equals("world0/final.fsh"));
        check("no program for the Nether when only world0 has one", ProgramSet.discover(blue, "world-1").find("final") == null);
        check("a dimension without a folder uses the shared files only", ProgramSet.discover(blue, null).find("final") == null);

        // Shared files and the dimension's own: the dimension wins.
        Map<String, String> shared = finalFiles();
        shared.put("final.vsh", shared.remove("world0/final.vsh"));
        shared.put("final.fsh", shared.remove("world0/final.fsh"));
        shared.put("world-1/final.fsh", TestPacks.finalFragment("fragColor = color * 2.0;"));
        shared.put("world-1/final.vsh", "#version 330\nvoid main() { gl_Position = vec4(1.0); }\n");
        ZipPack both = openStandard(dir.resolve("Shared.zip"), shared);
        check("the shared program serves a dimension without its own", ProgramSet.discover(both, "world0").find("final").fragment().equals("final.fsh"));
        check("a dimension's own program wins over the shared one", ProgramSet.discover(both, "world-1").find("final").fragment().equals("world-1/final.fsh"));
        check("a folder of another dimension is ignored", ProgramSet.discover(both, "world1").find("final").fragment().equals("final.fsh"));

        // Programs this version does not run are listed, not an error, as long as something runs.
        Map<String, String> more = finalFiles();
        more.put("world0/gbuffers_terrain.fsh", "#version 330\n");
        more.put("world0/gbuffers_terrain.vsh", "#version 330\n");
        more.put("world0/shadow.vsh", "#version 330\n");
        more.put("lib/common.glsl", "// not a program\n");
        ZipPack extra = openStandard(dir.resolve("More.zip"), more);
        validates("programs that are not run yet do not stop a pack that has final", extra);
        check("they are listed", ProgramSet.discover(extra, "world0").unsupported().equals(java.util.List.of("gbuffers_terrain", "shadow")));
        check("library files are not programs", ProgramSet.discover(extra, "world0").unsupported().stream().noneMatch(n -> n.contains("common")));

        // Switching a program off.
        Map<String, String> off = finalFiles();
        off.put("shaders.properties", "program.final.enabled=false\n");
        check("program.final.enabled=false switches it off", ProgramSet.discover(openStandard(dir.resolve("Off.zip"), off), "world0").find("final") == null);
        Map<String, String> on = finalFiles();
        on.put("shaders.properties", "program.final.enabled=true\n");
        check("program.final.enabled=true leaves it on", ProgramSet.discover(openStandard(dir.resolve("On.zip"), on), "world0").find("final") != null);

        // Packs that cannot be used say why.
        Map<String, String> onlyComposite = new java.util.LinkedHashMap<>();
        onlyComposite.put("world0/gbuffers_terrain.fsh", "#version 330\n");
        onlyComposite.put("world0/gbuffers_terrain.vsh", "#version 330\n");
        rejects("a pack with no program this version runs is rejected, naming what it has", openStandard(dir.resolve("OnlyComposite.zip"), onlyComposite), "gbuffers_terrain");
        Map<String, String> halfway = finalFiles();
        halfway.remove("world0/final.vsh");
        rejects("a program with one of its two files is rejected", openStandard(dir.resolve("Half.zip"), halfway), "needs both");
        Map<String, String> sampler = finalFiles();
        sampler.put("world0/final.fsh", "#version 330\nuniform sampler2D colortex0;\nuniform sampler2D shadowtex0;\nvoid main() {}\n");
        rejects("a program reading a texture that does not exist yet is rejected", openStandard(dir.resolve("Sampler.zip"), sampler), "shadowtex0");
        try {
            TestPacks.writeStandard(dir.resolve("Empty.zip"), Map.of());
            ZipPack.open(dir.resolve("Empty.zip"));
            check("a ZIP with nothing in shaders/ is rejected", false);
        } catch (PackException e) {
            check("a ZIP with nothing in shaders/ is rejected (" + e.getMessage() + ")", true);
        }

        // #include: from the shaders folder with a leading slash, from the including file's folder without one.
        Map<String, String> includes = finalFiles();
        includes.put("lib/color.glsl", "#include \"/lib/inner.glsl\"\nvec4 mx_tint(vec4 c) { return c; }\n");
        includes.put("lib/inner.glsl", "// inner\n");
        includes.put("world0/local.glsl", "// local\n");
        includes.put("world0/final.fsh", "#version 330\n#include \"/lib/color.glsl\"\n#include \"local.glsl\"\n#include \"../lib/inner.glsl\"\nuniform sampler2D colortex0;\nvoid main() {}\n");
        ZipPack included = openStandard(dir.resolve("Includes.zip"), includes);
        String text = included.load("world0/final.fsh");
        check("includes from the shaders folder, from the file's folder and with .. all arrive", text.contains("mx_tint") && text.contains("// local") && text.contains("// inner"));
        check("a file included twice goes in once", text.indexOf("// inner") == text.lastIndexOf("// inner"));
        Map<String, String> missing = finalFiles();
        missing.put("world0/final.fsh", "#version 330\n#include \"/lib/nothing.glsl\"\n");
        rejects("an include that is not in the pack is rejected", openStandard(dir.resolve("MissingInclude.zip"), missing), "nothing.glsl");
        Map<String, String> escape = finalFiles();
        escape.put("world0/final.fsh", "#version 330\n#include \"../../x.glsl\"\n");
        rejects("an include that leaves the shaders folder is rejected", openStandard(dir.resolve("Escape.zip"), escape), "leaves");

        // Other ZIPs are not ours.
        TestPacks.write(dir.resolve("Other.zip"), null, Map.of());
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve("Unrelated.zip")))) {
            out.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            out.write("hi".getBytes());
            out.closeEntry();
        }
        check("a ZIP with no shaders is not a pack", !ZipPack.isPack(dir.resolve("Unrelated.zip")));

        // Discovery lists them next to the older packs.
        java.util.List<PackManager.Entry> entries = PackManager.scan(dir);
        check("standard packs are listed", entry(entries, "SolidBlue.zip") != null && entry(entries, "SolidBlue.zip").selectable());
        check("an unusable standard pack is listed with the reason", entry(entries, "OnlyComposite.zip") != null && !entry(entries, "OnlyComposite.zip").selectable() && entry(entries, "OnlyComposite.zip").error().contains("gbuffers_terrain"));
    }

    // ---- the plan of a standard pack: stages, buffers, flips ----

    private static final String VERTEX = "#version 330\nout vec2 texcoord;\nvoid main() { gl_Position = vec4(0.0); texcoord = vec2(0.0); }\n";

    /** A fragment program with these lines at the top of an otherwise empty {@code main}. */
    private static String fragment(final String header) {
        return "#version 330\n" + header + "\nin vec2 texcoord;\nvoid main() {}\n";
    }

    private static IrisPlan planOf(final Path dir, final String name, final Map<String, String> programs, final String properties) throws Exception {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> program : programs.entrySet()) {
            files.put(program.getKey() + ".vsh", VERTEX);
            files.put(program.getKey() + ".fsh", program.getValue());
        }
        if (properties != null) files.put("shaders.properties", properties);
        return IrisPlan.build(openStandard(dir.resolve(name + ".zip"), files), null);
    }

    private static void planFails(final String what, final Path dir, final String name, final Map<String, String> programs, final String properties, final String mentions) throws Exception {
        try {
            planOf(dir, name, programs, properties);
            check(what, false);
        } catch (PackException e) {
            check(what + " (" + e.getMessage() + ")", e.getMessage().contains(mentions));
        }
    }

    private static List<String> names(final List<IrisPlan.Step> steps) {
        List<String> result = new ArrayList<>();
        for (IrisPlan.Step step : steps) result.add(step.program().name());
        return result;
    }

    private static void irisPlans(final Path dir) throws Exception {
        Files.createDirectories(dir);
        String one = fragment("uniform sampler2D colortex0;\nout vec4 color;");

        // Order: the stages in Iris's order, and numbers counted as numbers (composite10 comes after composite2).
        Map<String, String> order = new java.util.LinkedHashMap<>();
        for (String name : List.of("composite10", "composite", "composite2", "deferred", "prepare", "begin", "final", "composite1")) order.put(name, one);
        IrisPlan plan = planOf(dir, "Order", order, null);
        check("composite programs run by number, not by name", names(plan.steps(ProgramSet.Stage.COMPOSITE)).equals(List.of("composite", "composite1", "composite2", "composite10")));
        check("each stage has its own programs", names(plan.steps(ProgramSet.Stage.BEGIN)).equals(List.of("begin")) && names(plan.steps(ProgramSet.Stage.PREPARE)).equals(List.of("prepare"))
                && names(plan.steps(ProgramSet.Stage.DEFERRED)).equals(List.of("deferred")) && plan.finalStep().program().name().equals("final"));
        check("something runs after the world", plan.afterWorld());

        // What a program writes.
        Map<String, String> writes = new java.util.LinkedHashMap<>();
        writes.put("composite", fragment("uniform sampler2D colortex0;\n/* RENDERTARGETS: 0,3,7 */\nlayout(location = 0) out vec4 a;\nlayout(location = 1) out vec4 b;\nlayout(location = 2) out vec4 c;"));
        writes.put("composite1", fragment("/* DRAWBUFFERS:12 */\nlayout(location = 0) out vec4 a;\nlayout(location = 1) out vec4 b;"));
        writes.put("composite2", fragment("out vec4 outColor0;\nout vec4 outColor1;"));
        writes.put("composite3", fragment("layout(location = 0) out vec4 a;"));
        IrisPlan w = planOf(dir, "Writes", writes, null);
        List<IrisPlan.Step> steps = w.steps(ProgramSet.Stage.COMPOSITE);
        check("RENDERTARGETS gives the buffers in order", java.util.Arrays.equals(steps.get(0).writes(), new int[] {0, 3, 7}));
        check("DRAWBUFFERS gives the buffers from its digits", java.util.Arrays.equals(steps.get(1).writes(), new int[] {1, 2}));
        check("without either, as many buffers as the program has outputs, from 0", java.util.Arrays.equals(steps.get(2).writes(), new int[] {0, 1}) && java.util.Arrays.equals(steps.get(3).writes(), new int[] {0}));
        check("only the buffers the programs touch exist", w.buffers().keySet().equals(java.util.Set.of(0, 1, 2, 3, 7)));
        planFails("more than 8 buffers at once is refused", dir, "Nine", Map.of("composite", fragment("/* RENDERTARGETS: 0,1,2,3,4,5,6,7,8 */")), null, "at most 8");
        planFails("a buffer past colortex15 is refused", dir, "Sixteen", Map.of("composite", fragment("/* RENDERTARGETS: 16 */")), null, "colortex16");
        planFails("the same buffer twice is refused", dir, "Twice", Map.of("composite", fragment("/* RENDERTARGETS: 2,2 */")), null, "twice");

        // Flips: on by default, off when the pack says so; a program cannot read what it draws into in place.
        Map<String, String> flips = new java.util.LinkedHashMap<>();
        flips.put("composite", fragment("/* RENDERTARGETS: 0,1 */\nlayout(location = 0) out vec4 a;\nlayout(location = 1) out vec4 b;"));
        IrisPlan flipped = planOf(dir, "Flips", flips, null);
        check("flipping is on for every buffer by default", !flipped.steps(ProgramSet.Stage.COMPOSITE).get(0).inPlace()[0] && !flipped.steps(ProgramSet.Stage.COMPOSITE).get(0).inPlace()[1]);
        IrisPlan unflipped = planOf(dir, "NoFlips", flips, "flip.composite.colortex1=false\n");
        check("flip.<program>.<buffer>=false turns it off for that buffer only", !unflipped.steps(ProgramSet.Stage.COMPOSITE).get(0).inPlace()[0] && unflipped.steps(ProgramSet.Stage.COMPOSITE).get(0).inPlace()[1]);
        planFails("a program that reads a buffer it draws into in place is refused", dir, "Hazard",
                Map.of("composite", fragment("uniform sampler2D colortex1;\n/* RENDERTARGETS: 1 */\nlayout(location = 0) out vec4 a;")), "flip.composite.colortex1=false\n", "colortex1");
        IrisPlan readsAndWrites = planOf(dir, "ReadWrite", Map.of("composite", fragment("uniform sampler2D colortex1;\n/* RENDERTARGETS: 1 */\nlayout(location = 0) out vec4 a;")), null);
        check("reading a buffer and drawing into it is fine with flipping on", readsAndWrites.steps(ProgramSet.Stage.COMPOSITE).get(0).reads(1));

        // Formats and clearing.
        Map<String, String> formats = new java.util.LinkedHashMap<>();
        formats.put("composite", fragment("uniform sampler2D colortex0;\nuniform sampler2D colortex1;\nuniform sampler2D colortex2;\nuniform sampler2D colortex4;\n"
                + "const int colortex1Format = RGBA16F;\nconst int colortex2Format = R32F;\nconst bool colortex2Clear = false;\nconst vec4 colortex4ClearColor = vec4(0.25, 0.5, 0.75, 1.0);\n/* RENDERTARGETS: 0 */\nlayout(location = 0) out vec4 a;"));
        IrisPlan f = planOf(dir, "Formats", formats, null);
        check("buffers are RGBA8 unless a program says otherwise", f.buffers().get(0).format() == com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
        check("colortexNFormat sets the format", f.buffers().get(1).format() == com.mojang.blaze3d.GpuFormat.RGBA16_FLOAT && f.buffers().get(2).format() == com.mojang.blaze3d.GpuFormat.R32_FLOAT);
        check("buffers clear every frame unless colortexNClear is false", f.buffers().get(0).clear() && f.buffers().get(1).clear() && !f.buffers().get(2).clear());
        float[] fog = {0.1F, 0.2F, 0.3F, 1.0F};
        check("default clear colors: buffer 0 the fog, buffer 1 white, the others transparent black",
                java.util.Arrays.equals(f.buffers().get(0).colorToClear(fog), fog) && java.util.Arrays.equals(f.buffers().get(1).colorToClear(fog), new float[] {1, 1, 1, 1})
                        && java.util.Arrays.equals(f.buffers().get(2).colorToClear(fog), new float[] {0, 0, 0, 0}));
        check("colortexNClearColor sets the clear color", java.util.Arrays.equals(f.buffers().get(4).colorToClear(fog), new float[] {0.25F, 0.5F, 0.75F, 1.0F}));
        planFails("a format that does not exist here is refused", dir, "BadFormat", Map.of("composite", fragment("uniform sampler2D colortex0;\nconst int colortex0Format = RGBA32UI;")), null, "RGBA32UI");

        // Depth and old names.
        IrisPlan depth = planOf(dir, "Depth", Map.of("composite", fragment("uniform sampler2D depthtex0;\nuniform sampler2D depthtex2;\nuniform sampler2D gaux1;")), null);
        check("depthtex0 and depthtex2 are used, depthtex1 is not asked for by name", depth.usesDepth(0) && !depth.usesDepth(1) && depth.usesDepth(2));
        check("an older sampler name is the buffer it stands for", depth.steps(ProgramSet.Stage.COMPOSITE).get(0).reads(4) && depth.buffers().containsKey(4));
        check("buffer 0 exists whenever anything runs after the world", planOf(dir, "OnlyFinal", Map.of("final", fragment("uniform sampler2D depthtex0;")), null).buffers().containsKey(0));

        // What it cannot do yet.
        Map<String, String> notYet = new java.util.LinkedHashMap<>();
        notYet.put("composite", one);
        notYet.put("shadowcomp", one);
        notYet.put("gbuffers_water", one);
        IrisPlan not = planOf(dir, "NotYet", notYet, null);
        check("shadowcomp and gbuffers programs are reported, not run", not.notes().stream().anyMatch(n -> n.contains("shadowcomp")) && not.notes().stream().anyMatch(n -> n.contains("gbuffers_water")) && not.stepCount() == 1);
        Map<String, String> gs = new java.util.LinkedHashMap<>();
        gs.put("composite.vsh", VERTEX);
        gs.put("composite.fsh", one);
        gs.put("composite.gsh", "#version 330\n");
        try {
            ProgramSet.validate(openStandard(dir.resolve("Geometry.zip"), gs));
            check("a program with a geometry shader is refused", false);
        } catch (PackException e) {
            check("a program with a geometry shader is refused (" + e.getMessage() + ")", e.getMessage().contains("geometry"));
        }
        check("a program with only a compute shader is listed as not run", ProgramSet.discover(openStandard(dir.resolve("Compute.zip"), Map.of("composite.csh", "#version 430\n", "final.vsh", VERTEX, "final.fsh", one)), null)
                .unsupported().contains("composite (compute only)"));
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
        // A shader pack in the Iris layout that this version cannot run: listed, with the reason.
        TestPacks.write(folder.resolve("SomeIrisPack.zip"), null, Map.of("world0/gbuffers_terrain.fsh", "void main() {}", "world0/gbuffers_terrain.vsh", "void main() {}"));
        Files.writeString(folder.resolve("notes.txt"), "not a zip");
        // The built-in pack's name, taken by a ZIP.
        TestPacks.writeBuiltinCopy(folder.resolve(BuiltinPack.NAME + ".zip"));
        Files.writeString(folder.resolve("Damaged.zip"), "this is not a zip file");

        List<PackManager.Entry> entries = PackManager.scan(folder);
        List<String> ids = new ArrayList<>();
        for (PackManager.Entry entry : entries) ids.add(entry.id());
        check("built-in pack is always first", ids.get(0).equals(PackManager.BUILTIN_ID));
        check("complete ZIPs are listed", ids.contains("RedToBlue.zip") && ids.contains("BlueToRed.zip"));
        check("an Iris-layout ZIP is listed, and cannot be chosen when nothing in it runs", ids.contains("SomeIrisPack.zip") && !entry(entries, "SomeIrisPack.zip").selectable());
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
