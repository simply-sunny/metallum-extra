package com.metallumextra;

import com.metallumextra.shader.pack.BuiltinPack;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.IrisTerrain;
import com.metallumextra.shader.IrisWorldAdapter;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.PackOption;
import com.metallumextra.shader.pack.PackOptions;
import com.metallumextra.shader.pack.StandardOptions;
import com.metallumextra.shader.pack.OptionExpression;
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
            irisUniforms();
            irisTerrain(work.resolve("terrain"));
            irisWorld(work.resolve("world"));
            standardOptions(work.resolve("standardOptions"));
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
    private static final Pattern UNIFORM_LINE = Pattern.compile("iris frame (\\d+) program (\\w+) uniform (\\w+)((?: -?[\\d.]+)+)");
    private static final Pattern FLOATS_LINE = Pattern.compile("iris frame (\\d+) program (\\w+) colortex(\\d+) floats (\\d+)x(\\d+) row0(.*)");
    private static final Pattern PIXEL = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+)\\)");

    /** Everything the log said while one pack was the one asked for: from its {@code pack} step to the next. */
    private static final class Section {
        final String pack;
        final List<Run> runs = new ArrayList<>();
        final List<Pixels> pixels = new ArrayList<>();
        /** (frame, program) to what the game said a uniform was (name to values). */
        final Map<String, Map<String, double[]>> uniforms = new java.util.LinkedHashMap<>();
        /** (frame, program, buffer) to the first pixels of the top row, as read back from the GPU. */
        final Map<String, double[][]> rows = new java.util.LinkedHashMap<>();

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
            Matcher uniform = UNIFORM_LINE.matcher(line);
            if (uniform.find()) {
                String[] parts = uniform.group(4).strip().split(" ");
                double[] value = new double[parts.length];
                for (int i = 0; i < parts.length; i++) value[i] = Double.parseDouble(parts[i]);
                current.uniforms.computeIfAbsent(uniform.group(1) + "/" + uniform.group(2), k -> new java.util.LinkedHashMap<>()).put(uniform.group(3), value);
                continue;
            }
            Matcher floats = FLOATS_LINE.matcher(line);
            if (floats.find()) {
                List<double[]> row = new ArrayList<>();
                Matcher point = Pattern.compile("\\(([^)]*)\\)").matcher(floats.group(6));
                while (point.find()) {
                    String[] parts = point.group(1).split(",");
                    double[] pixel = new double[4];
                    for (int i = 0; i < 4; i++) pixel[i] = Double.parseDouble(parts[i]);
                    row.add(pixel);
                }
                current.rows.put(floats.group(1) + "/" + floats.group(2) + "/" + floats.group(3), row.toArray(new double[0][]));
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

        // The standard uniforms: what the shader read from the block is what the game computed.
        Section uniforms = get.apply("IrisUniforms");
        int compared = 0, mismatched = 0;
        String first = "";
        for (Map.Entry<String, Map<String, double[]>> entry : uniforms.uniforms.entrySet()) {
            double[][] row = uniforms.rows.get(entry.getKey() + "/1");
            if (row == null) continue;
            int pixel = 0;
            for (IrisUniforms.Uniform uniform : IrisUniforms.ALL) {
                if (uniform.gbuffers() || TestPacks.skippedInDump(uniform.name())) continue;
                double[] expected = entry.getValue().get(uniform.name());
                int width = uniform.type().equals("mat4") ? 4 : 1;
                for (int p = 0; p < width; p++, pixel++) {
                    int components = uniform.type().equals("mat4") ? 4 : expected.length;
                    for (int c = 0; c < components; c++) {
                        double want = uniform.type().equals("mat4") ? expected[p * 4 + c] : expected[c];
                        double got = row[pixel][c];
                        compared++;
                        if (Math.abs(want - got) > 1e-5 + 1e-5 * Math.abs(want)) {
                            mismatched++;
                            if (first.isEmpty()) first = uniform.name() + "[" + p + "][" + c + "] game " + want + " shader " + got;
                        }
                    }
                }
            }
        }
        check("IrisUniforms: the shader read all " + compared + " numbers of the block as the game computed them" + (first.isEmpty() ? "" : " (first difference: " + first + ")"), compared > 56 * 3 && mismatched == 0);
        double[] width = null, cameraAt = null, sunAngle = null, sunPos = null, upPos = null, far = null, previousCamera = null, worldTime = null;
        for (Map<String, double[]> frameValues : uniforms.uniforms.values()) {
            width = frameValues.get("viewWidth");
            cameraAt = frameValues.get("cameraPosition");
            previousCamera = frameValues.get("previousCameraPosition");
            sunAngle = frameValues.get("sunAngle");
            sunPos = frameValues.get("sunPosition");
            upPos = frameValues.get("upPosition");
            far = frameValues.get("far");
            worldTime = frameValues.get("worldTime");
        }
        check("IrisUniforms: the camera is where the player was put (0.5, about 203.6, -8.5) and was there the frame before",
                cameraAt != null && Math.abs(cameraAt[0] - 0.5) < 1e-3 && Math.abs(cameraAt[1] - 203.62) < 0.1 && Math.abs(cameraAt[2] + 8.5) < 1e-3 && java.util.Arrays.equals(cameraAt, previousCamera));
        check("IrisUniforms: at time 6000 it is noon: worldTime 6000, sunAngle 0.25, and the sun is straight up (view-space sun parallel to up)",
                worldTime != null && worldTime[0] == 6000 && Math.abs(sunAngle[0] - 0.25) < 1e-3
                        && (sunPos[0] * upPos[0] + sunPos[1] * upPos[1] + sunPos[2] * upPos[2]) / (100.0 * 100.0) > 0.999);
        check("IrisUniforms: the screen width is the picture's, and far is a whole number of chunks", width != null && width[0] == picture(folder, "IrisUniforms").getWidth() && far[0] > 0 && far[0] % 16 == 0);

        // The view-space distance of the wall rebuilt from depth: 5.5 blocks along the view direction (the camera is at z -8.5, the wall's face at -3).
        Section viewDepth = get.apply("IrisViewDepth");
        int rowsSeen = 0;
        boolean wallDistance = true;
        String seenDistance = "";
        for (Map.Entry<String, double[][]> row : viewDepth.rows.entrySet()) {
            if (!row.getKey().endsWith("/1")) continue;
            rowsSeen++;
            for (double[] pixel : row.getValue()) {
                wallDistance &= Math.abs(pixel[0] - 5.5) < 0.02 && Math.abs(pixel[2] - 5.5) < 0.02;
                if (seenDistance.isEmpty()) seenDistance = String.format("%.4f", pixel[0]);
            }
        }
        check("IrisViewDepth: gbufferProjectionInverse and depthtex1/depthtex0 give the wall's distance, 5.5 (first pixel " + seenDistance + ")", rowsSeen >= 2 && wallDistance);

        // Terrain drawn by the pack's gbuffers_terrain: it ran (magenta), what it saw of each quad's direction and light reached its buffers,
        // and its geometry is where the game's is (the wall's distance, rebuilt from depth).
        java.awt.image.BufferedImage terrain = picture(folder, "IrisTerrain");
        double magenta = share(terrain, 255, 0, 255, 0.0, 0.32);
        check(String.format("IrisTerrain: the terrain is drawn by the pack's program: %.1f%% of the left third is its magenta", magenta * 100), magenta > 0.95);
        double wallNormal = share(terrain, 128, 128, 0, 0.34, 0.65);
        double groundNormal = share(terrain, 128, 255, 128, 0.34, 0.65);
        double flipped = share(terrain, 128, 128, 255, 0.34, 0.65);
        check(String.format("IrisTerrain: vaNormal of the wall (facing the camera, -z) is (0, 0, -1): %.1f%% of the middle third is its color, %.1f%% the ground's (0, 1, 0), %.1f%% the opposite wall normal",
                wallNormal * 100, groundNormal * 100, flipped * 100), wallNormal > 0.7 && groundNormal > 0.03 && flipped < 0.01);
        java.awt.image.BufferedImage lightThird = terrain.getSubimage((int) (terrain.getWidth() * 0.68), 0, (int) (terrain.getWidth() * 0.3), terrain.getHeight());
        long sky = 0, sampled = 0;
        for (int x = 0; x < lightThird.getWidth(); x += 2) {
            for (int y = 0; y < lightThird.getHeight() * 0.8; y += 2) {
                int rgb = lightThird.getRGB(x, y);
                if (((rgb >> 8) & 255) > 200 && ((rgb >> 16) & 255) < 20) sky++;
                sampled++;
            }
        }
        check(String.format("IrisTerrain: vaUV2 gives no block light and full sky light on the open wall: %.1f%% of the right third", 100.0 * sky / sampled), sky > sampled * 0.7);
        Section terrainSection = get.apply("IrisTerrain");
        int terrainRows = 0;
        boolean terrainDistance = true;
        String terrainSeen = "";
        for (Map.Entry<String, double[][]> row : terrainSection.rows.entrySet()) {
            if (!row.getKey().endsWith("/3")) continue;
            terrainRows++;
            for (double[] pixel : row.getValue()) {
                terrainDistance &= Math.abs(pixel[0] - 5.5) < 0.02;
                if (terrainSeen.isEmpty()) terrainSeen = String.format("%.4f", pixel[0]);
            }
        }
        check("IrisTerrain: the geometry the pack's vertex shader made is where it belongs: the wall is 5.5 from the camera in depth (first pixel " + terrainSeen + ")", terrainRows >= 2 && terrainDistance);

        // The game's own pipelines drawn with the pack's programs: each kind of thing the color of the program that drew it, in both buffers.
        java.awt.image.BufferedImage world = picture(folder, "IrisWorld");
        double green = share(world, 0, 255, 0, 0.0, 0.49), greenId = share(world, 0, 255, 0, 0.51, 1.0);
        double blue = share(world, 0, 0, 255, 0.0, 0.49), blueId = share(world, 0, 0, 255, 0.51, 1.0);
        double yellow = share(world, 255, 255, 0, 0.0, 0.49), yellowId = share(world, 255, 255, 0, 0.51, 1.0);
        double cyan = share(world, 0, 255, 255, 0.0, 0.49), cyanId = share(world, 0, 255, 255, 0.51, 1.0);
        double magentaWorld = share(world, 255, 0, 255, 0.0, 0.49), magentaId = share(world, 255, 0, 255, 0.51, 1.0);
        check(String.format("IrisWorld: terrain is drawn by gbuffers_terrain (magenta %.1f%% of colortex0, %.1f%% of colortex1)", magentaWorld * 100, magentaId * 100), magentaWorld > 0.5 && Math.abs(magentaWorld - magentaId) < 0.02);
        check(String.format("IrisWorld: mobs, items and block entities are drawn by gbuffers_entities (green %.2f%% of colortex0, %.2f%% of colortex1)", green * 100, greenId * 100), green > 0.01 && Math.abs(green - greenId) < green * 0.1);
        check(String.format("IrisWorld: the falling block is drawn by gbuffers_block (blue %.3f%% of colortex0, %.3f%% of colortex1)", blue * 100, blueId * 100), blue > 0.0005 && Math.abs(blue - blueId) < blue * 0.2);   // the rain covers it differently in the two (it is blended in one)
        check(String.format("IrisWorld: particles are drawn by gbuffers_particles (yellow %.3f%% of colortex0, %.3f%% of colortex1)", yellow * 100, yellowId * 100), yellow > 0.0005 && Math.abs(yellow - yellowId) < yellow * 0.1);
        check(String.format("IrisWorld: rain is drawn by gbuffers_weather (cyan %.3f%% of colortex0, %.3f%% of colortex1)", cyan * 100, cyanId * 100), cyan > 0.0002 && Math.abs(cyan - cyanId) < cyan * 0.1);
        check("IrisWorld: the programs do not draw each other's things: no green on the wall's rows far from the mobs", share(world, 0, 255, 0, 0.0, 0.05) < 0.002);

        // The shadow map: the pack's shadow program drew it, and terrain tests itself against it.
        java.awt.image.BufferedImage shadow = picture(folder, "IrisShadow");
        double lit = share(shadow, 0, 255, 0, 0.0, 0.49), dark = share(shadow, 255, 0, 0, 0.0, 0.49), mapBlue = share(shadow, 0, 0, 255, 0.51, 1.0);
        check(String.format("IrisShadow: terrain in the sun is green (%.1f%%) and terrain in the shade red (%.1f%%)", lit * 100, dark * 100), lit > 0.02 && dark > 0.1);
        check(String.format("IrisShadow: the shadow program's color is in shadowcolor0 where terrain is (blue %.1f%%)", mapBlue * 100), mapBlue > 0.2);

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
                {"14_iristerrain", false, false},
                {"15_builtin_after_terrain", true, true},
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
        java.awt.image.BufferedImage before = javax.imageio.ImageIO.read(folder.resolve("4_builtin_again.png").toFile());
        java.awt.image.BufferedImage after = javax.imageio.ImageIO.read(folder.resolve("15_builtin_after_terrain.png").toFile());
        check("after a pack that draws the terrain itself, the built-in shaders draw what they drew before", Math.abs(share(before, true) - share(after, true)) < 0.02 && Math.abs(share(before, false) - share(after, false)) < 0.02);
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

    // ---- the format, validation, reading ----

    private static void loading(final Path dir) throws Exception {
        Map<String, String> builtin = TestPacks.builtinFiles();

        // The built-in pack is an ordinary pack in the Iris layout, and a valid one in every dimension.
        BuiltinPack jar = new BuiltinPack();
        validates("built-in pack is valid", jar);
        check("the built-in pack has its files", jar.files().equals(builtin.keySet()) && jar.files().contains("shaders.properties") && jar.files().contains("final.fsh"));
        for (String dimension : ProgramSet.DIMENSIONS) {
            IrisPlan plan = IrisPlan.build(jar, dimension);
            check("the built-in pack plans in " + dimension + " (" + plan.stepCount() + " programs, " + plan.buffers().size() + " buffers)", plan.stepCount() >= 20 && plan.hasShadow() && plan.usesLightColors());
        }
        IrisPlan plan = IrisPlan.build(jar, "world0");
        check("it draws terrain, water, mobs, moving blocks, glowing mob parts, particles, rain, clouds and the sky itself",
                plan.terrainStep(IrisPlan.Layer.SOLID) != null && plan.terrainStep(IrisPlan.Layer.TRANSLUCENT).program().name().equals("gbuffers_water")
                        && plan.worldStep(IrisPlan.Use.ENTITY) != null && plan.worldStep(IrisPlan.Use.BLOCK) != null && plan.worldStep(IrisPlan.Use.EYES) != null
                        && plan.worldStep(IrisPlan.Use.PARTICLES) != null && plan.worldStep(IrisPlan.Use.WEATHER) != null && plan.worldStep(IrisPlan.Use.CLOUDS) != null
                        && plan.worldStep(IrisPlan.Use.SKY) != null);
        check("and leaves the hand to the game's own shaders", plan.worldStep(IrisPlan.Use.HAND) == null);
        check("the bloom levels have buffers of their own sizes", plan.buffers().get(6).widthOn(1000) == 500 && plan.buffers().get(10).widthOn(1000) == 31 && plan.buffers().get(0).fullSize());
        check("the shadow map is as the options say", plan.shadowResolution() == 2048 && plan.shadowDistance() == 96.0F);

        // Reading from the built-in pack and from an identical ZIP gives identical text.
        Path copy = dir.resolve("Copy.zip");
        TestPacks.writeBuiltinCopy(copy);
        ZipPack zip = ZipPack.open(copy);
        check("ZIP name is the file name without .zip", zip.name().equals("Copy"));
        boolean same = true;
        for (String file : builtin.keySet()) same &= builtin.get(file).equals(zip.read(file));
        check("every file reads the same from the jar and an identical ZIP", same);
        check("a program loads (with includes) the same from the jar and an identical ZIP", jar.load("gbuffers_terrain.fsh").replace(BuiltinPack.NAME, "Copy").equals(zip.load("gbuffers_terrain.fsh")));
        validates("identical ZIP is valid", zip);
        check("the options are found in the pack's files", jar.options().stream().map(PackOption::id).toList().containsAll(List.of("SHADOWS", "BLOOM", "WAVING", "shadowMapResolution", "shadowDistance")));

        // Two packs with a different version of one shader give each their own.
        TestPacks.writeColorPacks(dir);
        ZipPack redToBlue = ZipPack.open(dir.resolve("RedToBlue.zip"));
        ZipPack blueToRed = ZipPack.open(dir.resolve("BlueToRed.zip"));
        check("RedToBlue gives its own final.fsh", redToBlue.load("final.fsh").contains("vec3(0.0, 0.0, color.r)"));
        check("BlueToRed gives its own final.fsh", blueToRed.load("final.fsh").contains("vec3(color.b, 0.0, 0.0)"));
        check("the two packs differ only in final.fsh", differOnlyIn(redToBlue, blueToRed, "final.fsh", builtin.keySet()));
        validates("RedToBlue is valid", redToBlue);
        validates("BlueToRed is valid", blueToRed);

        // A program with one of its two files: rejected, and not borrowed from the built-in pack.
        Map<String, String> missing = TestPacks.builtinFiles();
        missing.remove("final.vsh");
        Path noFinal = dir.resolve("NoFinal.zip");
        TestPacks.write(noFinal, null, missing);
        ZipPack noFinalPack = ZipPack.open(noFinal);
        check("a pack without final.vsh does not have it", noFinalPack.read("final.vsh") == null);
        rejects("a pack with half a program is rejected", noFinalPack, "final.vsh");

        // An include that is not in the same ZIP: rejected.
        Map<String, String> noLibrary = TestPacks.builtinFiles();
        noLibrary.remove("lib/lighting.glsl");
        Path noLib = dir.resolve("NoLib.zip");
        TestPacks.write(noLib, null, noLibrary);
        rejects("a pack missing a library file its shaders include is rejected", ZipPack.open(noLib), "lighting.glsl");

        // A pack may arrange lib/ as it likes, as long as the includes resolve.
        Map<String, String> renamed = TestPacks.builtinFiles();
        String lighting = renamed.remove("lib/lighting.glsl");
        lighting = lighting.replace("#include \"color.glsl\"", "#include \"/lib/color.glsl\"").replace("#include \"noise.glsl\"", "#include \"/lib/noise.glsl\"").replace("#include \"light_colors.glsl\"", "#include \"/lib/light_colors.glsl\"");
        renamed.put("lib/deeper/folders/lighting.glsl", lighting);
        for (Map.Entry<String, String> file : new java.util.HashMap<>(renamed).entrySet()) {
            renamed.put(file.getKey(), file.getValue().replace("#include \"lighting.glsl\"", "#include \"deeper/folders/lighting.glsl\""));
        }
        Path moved = dir.resolve("Moved.zip");
        TestPacks.write(moved, null, renamed);
        validates("a pack that keeps its library in other folders is valid", ZipPack.open(moved));

        // A pack in the mod's earlier layout is listed, with the reason it cannot be used.
        Path old = dir.resolve("Old.zip");
        TestPacks.write(old, "{\"format\": 1}", Map.of("program/edges.fsh", "#version 330\n"));
        check("a ZIP with a pack.json is still recognized as a pack", ZipPack.isPack(old));
        try {
            ZipPack.open(old);
            check("but it cannot be opened", false);
        } catch (PackException e) {
            check("but it cannot be opened: " + e.getMessage(), e.getMessage().contains("earlier pack layout"));
        }
    }

    private static void options(final Path dir) throws Exception {
        PackOption quality = new PackOption("QUALITY", "Quality", List.of("Low", "Medium", "High", "Ultra"), 2, false);
        check("a choice keeps its values and default", quality.values().equals(List.of("Low", "Medium", "High", "Ultra")) && quality.defaultIndex() == 2);
        PackOptions.set("WithOptions", quality, 3);
        check("a chosen value is kept", PackOptions.get("WithOptions", quality) == 3);
        check("options are kept per pack", PackOptions.get("Other", quality) == 2);
        check("the choice is saved by label, so a reordered pack keeps it", quality.indexOf("Ultra") == 3 && new PackOption("QUALITY", "Quality", List.of("Ultra", "Low"), 1, false).indexOf("Ultra") == 0);
        check("a value the pack no longer has falls back to the default", new PackOption("QUALITY", "Quality", List.of("Low", "High"), 1, false).indexOf("Ultra") == 1);
        PackOptions.reset("WithOptions", List.of(quality));
        check("reset brings the defaults back", PackOptions.get("WithOptions", quality) == 2);
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
        check("it is named after the file", blue.name().equals("SolidBlue"));
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
        more.put("world0/gbuffers_skytextured.fsh", "#version 330\n");
        more.put("world0/gbuffers_skytextured.vsh", "#version 330\n");
        more.put("world0/shadow_water.vsh", "#version 330\n");
        more.put("lib/common.glsl", "// not a program\n");
        ZipPack extra = openStandard(dir.resolve("More.zip"), more);
        validates("programs that are not run yet do not stop a pack that has final", extra);
        check("they are listed", ProgramSet.discover(extra, "world0").unsupported().equals(java.util.List.of("gbuffers_skytextured", "shadow_water")));
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
        onlyComposite.put("world0/gbuffers_skytextured.fsh", "#version 330\n");
        onlyComposite.put("world0/gbuffers_skytextured.vsh", "#version 330\n");
        rejects("a pack with no program this version runs is rejected, naming what it has", openStandard(dir.resolve("OnlyComposite.zip"), onlyComposite), "gbuffers_skytextured");
        Map<String, String> halfway = finalFiles();
        halfway.remove("world0/final.vsh");
        rejects("a program with one of its two files is rejected", openStandard(dir.resolve("Half.zip"), halfway), "needs both");
        Map<String, String> sampler = finalFiles();
        sampler.put("world0/final.fsh", "#version 330\nuniform sampler2D colortex0;\nuniform sampler2D noisetex;\nvoid main() {}\n");
        rejects("a program reading a texture that does not exist yet is rejected", openStandard(dir.resolve("Sampler.zip"), sampler), "noisetex");
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
        check("an unusable standard pack is listed with the reason", entry(entries, "OnlyComposite.zip") != null && !entry(entries, "OnlyComposite.zip").selectable() && entry(entries, "OnlyComposite.zip").error().contains("gbuffers_skytextured"));
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
        notYet.put("gbuffers_skytextured", one);
        IrisPlan not = planOf(dir, "NotYet", notYet, null);
        check("shadowcomp and gbuffers programs are reported, not run", not.notes().stream().anyMatch(n -> n.contains("shadowcomp")) && not.notes().stream().anyMatch(n -> n.contains("gbuffers_skytextured")) && not.stepCount() == 1);
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

    // ---- programs that draw terrain ----

    private static final String TERRAIN_VERTEX = "#version 330 core\nin vec3 vaPosition;\nin vec2 vaUV0;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform vec3 chunkOffset;\nout vec2 uv;\n"
            + "void main() { gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0); uv = vaUV0; }\n";
    private static final String TERRAIN_FRAGMENT = "#version 330 core\nuniform sampler2D gtexture;\nin vec2 uv;\n/* RENDERTARGETS: 0,1 */\nlayout(location = 0) out vec4 a;\nlayout(location = 1) out vec4 b;\n"
            + "void main() { a = texture(gtexture, uv); b = vec4(1.0); }\n";

    private static IrisPlan terrainPlan(final Path dir, final String name, final Map<String, String> files) throws Exception {
        return IrisPlan.build(openStandard(dir.resolve(name + ".zip"), files), null);
    }

    private static Map<String, String> terrainFiles(final String... programs) {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (String program : programs) {
            files.put("" + program + ".vsh", TERRAIN_VERTEX);
            files.put("" + program + ".fsh", TERRAIN_FRAGMENT);
        }
        return files;
    }

    private static String terrainProgramFor(final IrisPlan plan, final IrisPlan.Layer layer) {
        IrisPlan.Step step = plan.terrainStep(layer);
        return step == null ? "none" : step.program().name();
    }

    private static void terrainFails(final String what, final Path dir, final String name, final Map<String, String> files, final String mentions) throws Exception {
        try {
            terrainPlan(dir, name, files);
            check(what, false);
        } catch (PackException e) {
            check(what + " (" + e.getMessage() + ")", e.getMessage().contains(mentions));
        }
    }

    private static void irisTerrain(final Path dir) throws Exception {
        Files.createDirectories(dir);
        // Which program draws which layer: its own, then the ones Iris falls back to.
        IrisPlan plain = terrainPlan(dir, "Plain", terrainFiles("gbuffers_terrain"));
        check("gbuffers_terrain draws every layer when it is all the pack has", terrainProgramFor(plain, IrisPlan.Layer.SOLID).equals("gbuffers_terrain")
                && terrainProgramFor(plain, IrisPlan.Layer.CUTOUT).equals("gbuffers_terrain") && terrainProgramFor(plain, IrisPlan.Layer.TRANSLUCENT).equals("gbuffers_terrain"));
        IrisPlan split = terrainPlan(dir, "Split", terrainFiles("gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_water"));
        check("terrain_solid, terrain_cutout and water draw their layers, each before gbuffers_terrain", terrainProgramFor(split, IrisPlan.Layer.SOLID).equals("gbuffers_terrain_solid")
                && terrainProgramFor(split, IrisPlan.Layer.CUTOUT).equals("gbuffers_terrain_cutout") && terrainProgramFor(split, IrisPlan.Layer.TRANSLUCENT).equals("gbuffers_water"));
        IrisPlan water = terrainPlan(dir, "WaterOnly", terrainFiles("gbuffers_terrain", "gbuffers_water"));
        check("water draws translucent terrain only", terrainProgramFor(water, IrisPlan.Layer.SOLID).equals("gbuffers_terrain") && terrainProgramFor(water, IrisPlan.Layer.TRANSLUCENT).equals("gbuffers_water"));
        check("without terrain, Iris falls back to textured_lit, then textured, then basic", terrainProgramFor(terrainPlan(dir, "Lit", terrainFiles("gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic")), IrisPlan.Layer.SOLID).equals("gbuffers_textured_lit")
                && terrainProgramFor(terrainPlan(dir, "Tex", terrainFiles("gbuffers_textured", "gbuffers_basic")), IrisPlan.Layer.CUTOUT).equals("gbuffers_textured")
                && terrainProgramFor(terrainPlan(dir, "Basic", terrainFiles("gbuffers_basic")), IrisPlan.Layer.TRANSLUCENT).equals("gbuffers_basic"));
        check("a pack with only fullscreen programs has no terrain program", terrainProgramFor(planOf(dir, "NoTerrain", Map.of("final", fragment("uniform sampler2D colortex0;\nout vec4 color;")), null), IrisPlan.Layer.SOLID).equals("none"));
        check("terrain programs draw into the buffers' main textures: nothing flips, and the first output is colortex0",
                plain.terrainStep(IrisPlan.Layer.SOLID).writes()[0] == 0 && plain.terrainStep(IrisPlan.Layer.SOLID).writes()[1] == 1 && plain.terrainStep(IrisPlan.Layer.SOLID).inPlace()[0] && plain.terrainStep(IrisPlan.Layer.SOLID).inPlace()[1]);
        check("the buffers the terrain program writes exist", plain.buffers().containsKey(0) && plain.buffers().containsKey(1));
        check("a pack with only gbuffers_terrain is valid, and it does not run afterwards", plain.stepCount() == 1 && !plain.afterWorld());
        validates("a pack with only gbuffers_terrain passes the checks", ZipPack.open(dir.resolve("Plain.zip")));

        // What cannot be provided is refused by name.
        Map<String, String> tangent = terrainFiles("gbuffers_terrain");
        tangent.put("gbuffers_terrain.vsh", TERRAIN_VERTEX.replace("in vec2 vaUV0;", "in vec2 vaUV0;\nin vec4 at_tangent;"));
        terrainFails("a vertex input that is not provided yet is refused", dir, "Tangent", tangent, "at_tangent");
        Map<String, String> strange = terrainFiles("gbuffers_terrain");
        strange.put("gbuffers_terrain.vsh", TERRAIN_VERTEX.replace("in vec2 vaUV0;", "in vec2 vaUV0;\nin vec3 myOwnInput;"));
        terrainFails("a vertex input Iris does not have is refused", dir, "Strange", strange, "myOwnInput");
        Map<String, String> wrongType = terrainFiles("gbuffers_terrain");
        wrongType.put("gbuffers_terrain.vsh", TERRAIN_VERTEX.replace("in vec2 vaUV0;", "in vec3 vaUV0;"));
        terrainFails("a vertex input of the wrong type is refused", dir, "WrongType", wrongType, "vec3");
        Map<String, String> compat = terrainFiles("gbuffers_terrain");
        compat.put("gbuffers_terrain.vsh", "#version 120\nvarying vec2 uv;\nvoid main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }\n");
        terrainFails("compatibility-profile GLSL is named in the error", dir, "Compat", compat, "compatibility profile");
        Map<String, String> compatFragment = new java.util.LinkedHashMap<>();
        compatFragment.put("final.vsh", VERTEX);
        compatFragment.put("final.fsh", "#version 330\nuniform sampler2D colortex0;\nvoid main() { gl_FragColor = texture2D(colortex0, vec2(0.0)); }\n");
        terrainFails("the same goes for a full-screen program", dir, "CompatFragment", compatFragment, "gl_FragColor");
        Map<String, String> commented = new java.util.LinkedHashMap<>();
        commented.put("final.vsh", VERTEX);
        commented.put("final.fsh", "#version 330\n// no gl_FragColor or varying here\n/* texture2D */\nuniform sampler2D colortex0;\nout vec4 c;\nvoid main() { c = texture(colortex0, vec2(0.0)); }\n");
        check("words in comments are not mistaken for compatibility code", terrainPlan(dir, "Commented", commented).finalStep() != null);
        Map<String, String> reads = terrainFiles("gbuffers_terrain");
        reads.put("gbuffers_terrain.fsh", TERRAIN_FRAGMENT.replace("uniform sampler2D gtexture;", "uniform sampler2D gtexture;\nuniform sampler2D colortex0;"));
        terrainFails("a program that draws the world cannot read a color buffer", dir, "ReadsBuffer", reads, "colortex0");
        Map<String, String> first = terrainFiles("gbuffers_terrain");
        first.put("gbuffers_terrain.fsh", TERRAIN_FRAGMENT.replace("RENDERTARGETS: 0,1", "RENDERTARGETS: 1,0"));
        terrainFails("a terrain program must write colortex0 first", dir, "First", first, "first output");
        Map<String, String> misplaced = new java.util.LinkedHashMap<>();
        misplaced.put("composite.vsh", VERTEX);
        misplaced.put("composite.fsh", fragment("uniform mat4 modelViewMatrix;\nout vec4 color;"));
        terrainFails("a full-screen program cannot declare the matrices of the programs that draw the world", dir, "Misplaced", misplaced, "modelViewMatrix");

        // The source is fitted to Sodium's pipeline.
        String vertex = IrisTerrain.adapt("#version 330 core\nin vec3 vaPosition;\nin vec4 vaColor;\nuniform vec3 chunkOffset;\nuniform mat4 textureMatrix;\nuniform sampler2D gtexture;\nvoid main() { }\n", true);
        check("the vertex stage loses its declarations of Iris's inputs and terrain uniforms", !vertex.contains("in vec3 vaPosition;") && !vertex.contains("in vec4 vaColor;") && !vertex.contains("uniform vec3 chunkOffset;")
                && !vertex.contains("uniform mat4 textureMatrix;") && !vertex.contains("uniform sampler2D gtexture;"));
        check("and gets Sodium's inputs, the definitions of Iris's names over them, and the atlas sampler Sodium binds", vertex.contains("in uvec2 a_Position;") && vertex.contains("#define vaPosition mx_va_position()")
                && vertex.contains("#define chunkOffset u_RegionOffset") && vertex.contains("uniform sampler2D u_BlockTex;") && vertex.contains("#define gtexture u_BlockTex") && vertex.indexOf("#version") < vertex.indexOf("a_Position"));
        String fragmentText = IrisTerrain.adapt("#version 330 core\nuniform sampler2D gtexture;\nuniform sampler2D lightmap;\nuniform float alphaTestRef;\nin vec2 uv;\nvoid main() { }\n", false);
        check("the fragment stage gets the samplers and alphaTestRef, and none of the vertex inputs", fragmentText.contains("#define gtexture u_BlockTex") && fragmentText.contains("#define lightmap u_LightTex")
                && fragmentText.contains("#define alphaTestRef ALPHA_CUTOUT") && !fragmentText.contains("a_Position") && !fragmentText.contains("uniform float alphaTestRef;"));
        check("an unused sampler is not declared", !IrisTerrain.adapt("#version 330 core\nvoid main() { }\n", false).contains("u_BlockTex"));
    }

    // ---- entities, items, particles ... ----

    private static final String WORLD_VERTEX = "#version 330 core\nin vec3 vaPosition;\nin vec2 vaUV0;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nout vec2 uv;\n"
            + "void main() { gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0); uv = vaUV0; }\n";

    private static RenderPipeline pipeline(final String name, final String shader, final String... flags) {
        RenderPipeline.Builder builder = RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "pipeline/" + name))
                .withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", shader))
                .withFragmentShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", shader))
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withColorTargetState(com.mojang.blaze3d.pipeline.ColorTargetState.DEFAULT);
        for (String flag : flags) builder.withShaderDefine(flag);
        return builder.build();
    }

    private static String useOf(final RenderPipeline pipeline) {
        IrisPlan.Use use = com.metallumextra.shader.IrisWorld.classify(pipeline);
        return use == null ? "none" : use.name();
    }

    /** The options a standard pack puts in its shader files, and the conditions of shaders.properties that read them. */
    private static void standardOptions(final Path dir) throws Exception {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("lib/options.glsl", "#define QUALITY 2 // [1 2 3]\n#define BLOOM\n//#define SHARP\n#define NOTHING_HERE 4\nconst int shadowMapResolution = 2048; // [1024 2048 4096]\n#ifndef OPTIONS_GLSL\n#define OPTIONS_GLSL\n#endif\n");
        files.put("lang/en_us.lang", "option.QUALITY=Quality\noption.BLOOM=Glow\n");
        List<PackOption> found = StandardOptions.discover(files);
        List<String> ids = found.stream().map(PackOption::id).toList();
        check("options are found in the shader files: values with a list, constants with a list, toggles on and off, and nothing else (" + ids + ")",
                ids.equals(List.of("QUALITY", "shadowMapResolution", "BLOOM", "SHARP")));
        PackOption quality = found.get(0), bloom = found.get(2), sharp = found.get(3);
        check("a value is the default listed, a toggle is on when its line is not commented out", quality.defaultIndex() == 1 && quality.values().equals(List.of("1", "2", "3"))
                && bloom.toggle() && bloom.defaultIndex() == 1 && sharp.toggle() && sharp.defaultIndex() == 0);
        check("labels come from the language file", quality.label().equals("Quality") && bloom.label().equals("Glow") && sharp.label().equals("SHARP"));
        PackOptions.set("OptionsPack", quality, 2);
        PackOptions.set("OptionsPack", bloom, 0);
        PackOptions.set("OptionsPack", sharp, 1);
        PackOptions.set("OptionsPack", found.get(1), 2);
        String applied = StandardOptions.apply(files.get("lib/options.glsl"), "OptionsPack", found);
        check("what was chosen is written into the lines: " + applied.replace('\n', '|'), applied.contains("#define QUALITY 3 //") && applied.contains("//#define BLOOM") && applied.contains("\n#define SHARP")
                && applied.contains("const int shadowMapResolution = 4096;") && applied.contains("#define NOTHING_HERE 4"));
        Map<String, String> values = StandardOptions.values("OptionsPack", found);
        check("the values for conditions: toggles are true or false", values.get("BLOOM").equals("false") && values.get("SHARP").equals("true") && values.get("QUALITY").equals("3"));
        PackOptions.reset("OptionsPack", found);
        for (Object[] row : new Object[][] {
                {"BLOOM", true}, {"!BLOOM", false}, {"BLOOM && QUALITY >= 2", true}, {"BLOOM && QUALITY > 2", false}, {"SHARP || QUALITY == 2", true},
                {"(SHARP || BLOOM) && !(QUALITY < 2)", true}, {"MISSING", false}, {"true", true}, {"false", false}, {"QUALITY != 2", false}}) {
            boolean result = OptionExpression.evaluate((String) row[0], StandardOptions.values("OptionsPack", found));
            check("condition '" + row[0] + "' is " + row[1], result == (Boolean) row[1]);
        }
        for (String bad : new String[] {"BLOOM &&", "(BLOOM", "BLOOM ?? 1"}) {
            try {
                OptionExpression.evaluate(bad, Map.of());
                check("a condition that cannot be read is refused: " + bad, false);
            } catch (PackException e) {
                check("a condition that cannot be read is refused: " + bad + " (" + e.getMessage() + ")", true);
            }
        }

        // What the preprocessor would leave: a sampler behind a switch that is off is not asked for.
        String conditional = "#version 330\n#ifdef BLOOM\nuniform sampler2D colortex1;\n#else\nuniform sampler2D colortex2;\n#endif\n#ifdef EMISSIVE\nuniform sampler2D colortex3;\n#endif\n"
                + "//#define SHARP\n#ifdef SHARP\nuniform sampler2D colortex4;\n#endif\n#if QUALITY >= 3\nuniform sampler2D colortex5;\n#endif\n";
        String kept = com.metallumextra.shader.pack.GlslConditions.strip("#define BLOOM\n#define QUALITY 2\n" + conditional, Set.of("BLOOM", "SHARP"));
        List<String> asked = ProgramSet.samplers(kept);
        check("a condition is decided from the file: BLOOM is on, SHARP is off, QUALITY is 2, and EMISSIVE (the game's) is left open: " + asked,
                asked.equals(List.of("colortex1", "colortex3")) && kept.split("\n", -1).length == ("#define BLOOM\n#define QUALITY 2\n" + conditional).split("\n", -1).length);

        // Programs switched by an option, and buffers of their own size.
        Map<String, String> pack = new java.util.LinkedHashMap<>();
        pack.put("world0/final.vsh", "#version 330\nvoid main() {}\n");
        pack.put("world0/final.fsh", "#version 330\nuniform sampler2D colortex0;\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n");
        pack.put("world0/composite.vsh", "#version 330\nvoid main() {}\n");
        pack.put("world0/composite.fsh", "#version 330\n#define GLOW // used\nuniform sampler2D colortex0;\n/* RENDERTARGETS: 4 */\nlayout(location = 0) out vec4 c;\nvoid main() { c = vec4(1.0); }\n");
        pack.put("shaders.properties", "program.composite.enabled=GLOW\nsize.buffer.colortex4=0.5 0.5\n");
        ZipPack options = openStandard(dir.resolve("Glow.zip"), pack);
        check("a program is on while its option is", ProgramSet.discover(options, "world0").find("composite") != null);
        PackOption glow = options.options().stream().filter(o -> o.id().equals("GLOW")).findFirst().orElseThrow();
        PackOptions.set("Glow", glow, 0);
        check("and off when the option is switched off", ProgramSet.discover(options, "world0").find("composite") == null);
        PackOptions.reset("Glow", options.options());
        IrisPlan sized = IrisPlan.build(options, "world0");
        check("a buffer can be a share of the screen's size", sized.buffers().get(4).scaleX() == 0.5F && sized.buffers().get(4).widthOn(1000) == 500 && sized.buffers().get(4).heightOn(601) == 300);
        check("the world's image stays full size", sized.buffers().get(0).fullSize());
    }

    private static void irisWorld(final Path dir) throws Exception {
        Files.createDirectories(dir);
        // What each of the game's pipelines draws.
        check("mobs and armor are entities, their translucent kin entities_translucent", useOf(pipeline("entity_solid", "core/entity")).equals("ENTITY") && useOf(pipeline("armor_cutout_no_cull", "core/entity")).equals("ENTITY")
                && useOf(pipeline("entity_translucent", "core/entity")).equals("ENTITY_TRANSLUCENT") && useOf(pipeline("armor_translucent", "core/entity")).equals("ENTITY_TRANSLUCENT"));
        check("items are drawn with the entity program too", useOf(pipeline("item_cutout", "core/item")).equals("ENTITY") && useOf(pipeline("item_translucent", "core/item")).equals("ENTITY_TRANSLUCENT"));
        check("blocks the game draws one at a time are block, translucent ones block_translucent", useOf(pipeline("solid_block", "core/block")).equals("BLOCK") && useOf(pipeline("cutout_block", "core/block")).equals("BLOCK")
                && useOf(pipeline("translucent_block", "core/block")).equals("BLOCK_TRANSLUCENT"));
        check("particles, translucent particles and weather", useOf(pipeline("opaque_particle", "core/particle")).equals("PARTICLES") && useOf(pipeline("translucent_particle", "core/particle")).equals("PARTICLES_TRANSLUCENT")
                && useOf(pipeline("weather_depth_write", "core/particle")).equals("WEATHER") && useOf(pipeline("weather_no_depth_write", "core/particle")).equals("WEATHER"));
        check("the sky, text, lines and the interface are left to the game's own shaders", useOf(pipeline("sky", "core/sky")).equals("none") && useOf(pipeline("text", "core/text")).equals("none")
                && useOf(pipeline("lines", "core/rendertype_lines")).equals("none") && useOf(pipeline("gui", "core/gui")).equals("none"));
        check("glowing entity layers are eyes, clouds are clouds", useOf(pipeline("eyes", "core/entity", "EMISSIVE")).equals("EYES") && useOf(pipeline("clouds", "core/rendertype_clouds")).equals("CLOUDS"));

        // Which program draws each kind: the first in Iris's chain.
        Map<String, String> all = new java.util.LinkedHashMap<>();
        for (String name : List.of("gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_block", "gbuffers_particles", "gbuffers_weather", "gbuffers_textured_lit", "gbuffers_terrain")) {
            all.put(name + ".vsh", WORLD_VERTEX.replace("#version 330 core", "#version 330 core\n"));
            all.put(name + ".fsh", "#version 330 core\nuniform sampler2D gtexture;\nin vec2 uv;\nlayout(location = 0) out vec4 c;\nvoid main() { c = texture(gtexture, uv); }\n");
        }
        // gbuffers_terrain's vertex shader is checked as terrain's, which has no overlay; these only use inputs both have.
        IrisPlan every = IrisPlan.build(openStandard(dir.resolve("Every.zip"), all), null);
        check("entities, their translucent kin, blocks, particles and weather each get their own program", every.worldStep(IrisPlan.Use.ENTITY).program().name().equals("gbuffers_entities")
                && every.worldStep(IrisPlan.Use.ENTITY_TRANSLUCENT).program().name().equals("gbuffers_entities_translucent") && every.worldStep(IrisPlan.Use.BLOCK).program().name().equals("gbuffers_block")
                && every.worldStep(IrisPlan.Use.PARTICLES).program().name().equals("gbuffers_particles") && every.worldStep(IrisPlan.Use.WEATHER).program().name().equals("gbuffers_weather"));
        check("translucent blocks fall back to block, translucent particles to textured_lit when particles has no translucent kin... here to particles",
                every.worldStep(IrisPlan.Use.BLOCK_TRANSLUCENT).program().name().equals("gbuffers_block") && every.worldStep(IrisPlan.Use.PARTICLES_TRANSLUCENT).program().name().equals("gbuffers_particles"));
        Map<String, String> litOnly = new java.util.LinkedHashMap<>();
        litOnly.put("gbuffers_textured_lit.vsh", all.get("gbuffers_textured_lit.vsh"));
        litOnly.put("gbuffers_textured_lit.fsh", all.get("gbuffers_textured_lit.fsh"));
        IrisPlan lit = IrisPlan.build(openStandard(dir.resolve("LitOnly.zip"), litOnly), null);
        check("with only gbuffers_textured_lit it draws entities, particles and weather, and terrain too", lit.worldStep(IrisPlan.Use.ENTITY).program().name().equals("gbuffers_textured_lit")
                && lit.worldStep(IrisPlan.Use.WEATHER).program().name().equals("gbuffers_textured_lit") && lit.terrainStep(IrisPlan.Layer.SOLID).program().name().equals("gbuffers_textured_lit"));
        check("a pack without any of them draws nothing itself", IrisPlan.build(openStandard(dir.resolve("None.zip"), Map.of("final.vsh", VERTEX, "final.fsh", fragment("uniform sampler2D colortex0;\nout vec4 c;"))), null).worldStep(IrisPlan.Use.ENTITY) == null);

        // What each kind of program may read.
        Map<String, String> blockId = new java.util.LinkedHashMap<>(litOnly);
        blockId.put("gbuffers_entities.vsh", WORLD_VERTEX.replace("in vec2 vaUV0;", "in vec2 vaUV0;\nin vec2 mc_Entity;"));
        blockId.put("gbuffers_entities.fsh", litOnly.get("gbuffers_textured_lit.fsh"));
        check("the block id may be read by any program (it is -1 outside terrain)", IrisPlan.build(openStandard(dir.resolve("BlockId.zip"), blockId), null).worldStep(IrisPlan.Use.ENTITY) != null);
        Map<String, String> overlay = terrainFiles("gbuffers_terrain");
        overlay.put("gbuffers_terrain.vsh", TERRAIN_VERTEX.replace("in vec2 vaUV0;", "in vec2 vaUV0;\nin ivec2 vaUV1;"));
        terrainFails("the overlay is not for terrain", dir, "Overlay", overlay, "vaUV1");
        Map<String, String> entityOverlay = new java.util.LinkedHashMap<>(litOnly);
        entityOverlay.put("gbuffers_entities.vsh", WORLD_VERTEX.replace("in vec2 vaUV0;", "in vec2 vaUV0;\nin ivec2 vaUV1;"));
        entityOverlay.put("gbuffers_entities.fsh", litOnly.get("gbuffers_textured_lit.fsh"));
        check("entities may read the overlay", IrisPlan.build(openStandard(dir.resolve("EntityOverlay.zip"), entityOverlay), null).worldStep(IrisPlan.Use.ENTITY) != null);
        Map<String, String> far = terrainFiles("gbuffers_entities");
        far.put("gbuffers_entities.fsh", TERRAIN_FRAGMENT.replace("RENDERTARGETS: 0,1", "RENDERTARGETS: 0,9"));
        terrainFails("the programs that draw the world cannot write past colortex7", dir, "Far", far, "colortex7");
        Map<String, String> worldBuffers = terrainFiles("gbuffers_terrain", "gbuffers_entities");
        worldBuffers.put("gbuffers_entities.fsh", TERRAIN_FRAGMENT.replace("RENDERTARGETS: 0,1", "RENDERTARGETS: 0,3"));
        IrisPlan buffers = terrainPlan(dir, "WorldBuffers", worldBuffers);
        check("the buffers any program drawing the world writes are the ones every pass has: here 1 (terrain) and 3 (entities)", buffers.worldBuffers().equals(java.util.Set.of(1, 3)));

        // Fitting a program to a pipeline.
        IrisWorldAdapter.Format entity = new IrisWorldAdapter.Format(true, true, true, true, true, false, true, true, false, false);
        String vertex = IrisWorldAdapter.adapt("#version 330 core\nin vec3 vaPosition;\nin ivec2 vaUV1;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform mat3 normalMatrix;\nuniform vec3 chunkOffset;\nvoid main() { }\n", true, entity);
        check("the vertex stage loses its declarations and gets the game's inputs and blocks, with Iris's names defined over them",
                !vertex.contains("in vec3 vaPosition;") && !vertex.contains("uniform mat4 modelViewMatrix;") && !vertex.contains("uniform mat3 normalMatrix;") && vertex.contains("in vec3 Position;") && vertex.contains("in ivec2 UV1;")
                        && vertex.contains("#define vaPosition Position") && vertex.contains("#define vaColor (Color * ColorModulator)") && vertex.contains("#define modelViewMatrix ModelViewMat") && vertex.contains("#define chunkOffset vec3(0.0)")
                        && vertex.contains("uniform DynamicTransforms") && vertex.indexOf("#version") < vertex.indexOf("Position;"));
        IrisWorldAdapter.Format particle = new IrisWorldAdapter.Format(true, true, false, true, false, false, true, true, false, false);
        String particleVertex = IrisWorldAdapter.adapt("#version 330 core\nvoid main() { }\n", true, particle);
        check("what a pipeline lacks gets a plain default: no normal faces up, no overlay is none, no color is the modulator", particleVertex.contains("#define vaNormal vec3(0.0, 1.0, 0.0)") && particleVertex.contains("#define vaUV1 ivec2(0, 10)")
                && !particleVertex.contains("in vec3 Normal;") && !particleVertex.contains("in ivec2 UV1;"));
        String block = IrisWorldAdapter.adapt("#version 330 core\nvoid main() { }\n", true, new IrisWorldAdapter.Format(true, true, false, true, false, true, true, true, false, false));
        check("the blocks the game draws one at a time are offset by the game's ModelOffset", block.contains("#define vaPosition (Position + ModelOffset)"));
        String fragmentText = IrisWorldAdapter.adapt("#version 330 core\nuniform sampler2D gtexture;\nuniform sampler2D lightmap;\nuniform float alphaTestRef;\nvoid main() { }\n", false, entity);
        check("the fragment stage reads the game's samplers: the entity's texture is Sampler0, the light map Sampler2", fragmentText.contains("#define gtexture Sampler0") && fragmentText.contains("#define lightmap Sampler2")
                && fragmentText.contains("#define alphaTestRef ALPHA_CUTOUT") && !fragmentText.contains("in vec3 Position;") && !fragmentText.contains("uniform float alphaTestRef;"));
        String remapped = IrisWorldAdapter.remapOutputs("layout(location = 0) out vec4 a;\nlayout(location = 1) out vec4 b;\n  out vec4 outColor2;\nlayout(location = 3) out vec4 d;\n", new int[] {0, 3, 5, 6});
        check("outputs move to the attachment of their buffer: 0, 1, 2, 3 become 0, 3, 5, 6", remapped.contains("layout(location = 0) out vec4 a;") && remapped.contains("layout(location = 3) out vec4 b;")
                && remapped.contains("layout(location = 5) out vec4 outColor2;") && remapped.contains("layout(location = 6) out vec4 d;"));
    }

    // ---- the standard uniforms ----

    private static org.joml.Matrix4f matrix(final Map<String, double[]> values, final String name) {
        float[] floats = new float[16];
        for (int i = 0; i < 16; i++) floats[i] = (float) values.get(name)[i];
        return new org.joml.Matrix4f().set(floats);
    }

    private static boolean sameMatrix(final org.joml.Matrix4f a, final org.joml.Matrix4f b, final float tolerance) {
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) if (Math.abs(a.get(c, r) - b.get(c, r)) > tolerance) return false;
        return true;
    }

    private static com.metallumextra.shader.IrisUniformValues.Inputs inputs(final org.joml.Matrix4f view, final org.joml.Matrix4f projection, final float sunAngle, final long ticks,
                                                                          final float rain, final double frameTime, final int skyLight) {
        return new com.metallumextra.shader.IrisUniformValues.Inputs(view, projection, 256.0F, 100.5, 64.0, -20.25, sunAngle, sunAngle + (float) Math.PI, 0.0F, ticks, 3,
                rain, 0.25F, 12.5, frameTime, 7, 1600, 900, 0.5F, 0.0F, 0.0F, 0.0F, 0.7F, 0.8F, 0.9F, 0.2F, 0.4F, 0.8F, 5, skyLight, 0);
    }

    private static void irisUniforms() throws Exception {
        // The block layout is std140: each member starts at a multiple of its alignment, and a float may follow a vec3 in its last four bytes.
        IrisUniforms.Layout layout = IrisUniforms.layout();
        check("std140 layout: the six matrices first (0, 64, ... 320), then vec3s at 384, 400 ...", layout.offsets().get("gbufferModelView") == 0 && layout.offsets().get("gbufferPreviousProjection") == 320
                && layout.offsets().get("cameraPosition") == 384 && layout.offsets().get("previousCameraPosition") == 400);
        int last = 0;
        boolean ordered = true;
        for (IrisUniforms.Uniform uniform : IrisUniforms.ALL) {
            int at = layout.offsets().get(uniform.name());
            ordered &= at >= last && at % (uniform.type().equals("float") || uniform.type().equals("int") ? 4 : uniform.type().equals("ivec2") ? 8 : 16) == 0;
            last = at;
        }
        check("every member is aligned for its type and they do not overlap, in the order of ALL (" + layout.size() + " bytes)", ordered && layout.size() % 16 == 0);
        check("ivec2 is aligned to 8, a float after it packs at 4, and mat3 takes 48", layout.offsets().get("eyeBrightness") % 8 == 0 && layout.offsets().get("eyeAltitude") == layout.offsets().get("eyeBrightnessSmooth") + 8
                && layout.offsets().get("normalMatrix") + 48 <= layout.size());

        // Loose declarations become one block that always has every member.
        String source = "#version 330\n#extension GL_ARB_foo : enable\nuniform sampler2D colortex0;\nuniform mat4 gbufferModelView;\nuniform vec3 cameraPosition, sunPosition; // where\nuniform float frameTimeCounter;\nvoid main() { }\n";
        List<String> declared = IrisUniforms.declared(source);
        check("declared uniforms come in layout order, and samplers are not among them", declared.equals(List.of("gbufferModelView", "cameraPosition", "sunPosition", "frameTimeCounter")));
        String rewritten = IrisUniforms.rewrite(source, declared);
        check("the loose declarations are gone and the samplers stay", !rewritten.contains("uniform mat4") && !rewritten.contains("uniform vec3") && !rewritten.contains("uniform float") && rewritten.contains("uniform sampler2D colortex0;"));
        check("the block follows the #version and #extension lines, holds every member in order, and names the ones not declared so no program can use them",
                rewritten.indexOf("#extension") < rewritten.indexOf("layout(std140) uniform IrisUniforms") && rewritten.indexOf("mat4 gbufferModelView;") < rewritten.indexOf("mat4 _mx_unused_gbufferModelViewInverse;")
                        && rewritten.contains("vec3 sunPosition;") && rewritten.contains("float frameTimeCounter;") && rewritten.contains("float _mx_unused_sunAngle;") && rewritten.contains("mat3 _mx_unused_normalMatrix;")
                        && rewritten.indexOf("layout(std140)") < rewritten.indexOf("void main"));
        check("every program gets the same block layout: the declarations differ only in names", IrisUniforms.rewrite("#version 330\nuniform float far;\n", List.of("far")).lines().filter(l -> l.contains(";")).count()
                == IrisUniforms.rewrite("#version 330\nuniform float near;\n", List.of("near")).lines().filter(l -> l.contains(";")).count());
        check("a program without standard uniforms is left as it is", IrisUniforms.rewrite("#version 330\nvoid main() {}\n", List.of()).equals("#version 330\nvoid main() {}\n"));
        check("the terrain's own uniforms (chunkOffset ...) are not in the block", IrisUniforms.declared("#version 330\nuniform vec3 chunkOffset;\nuniform float alphaTestRef;\n").isEmpty());
        for (String[] bad : new String[][] {
                {"uniform mat4 shadowModelViewMissing;", "custom uniforms"}, {"uniform int entityId;", "gbuffers"}, {"uniform float myOwnThing;", "custom uniforms"},
                {"uniform vec3 frameTimeCounter;", "declared vec3"}, {"uniform float cameraPosition[2];", "arrays"}}) {
            try {
                IrisUniforms.declared("#version 330\n" + bad[0] + "\n");
                check("refused: " + bad[0], false);
            } catch (PackException e) {
                check("refused: " + bad[0] + " (" + e.getMessage() + ")", e.getMessage().contains(bad[1]));
            }
        }

        // The values.
        org.joml.Matrix4f view = new org.joml.Matrix4f().rotateX(-0.3F).rotateY(0.5F);
        org.joml.Matrix4f game = new org.joml.Matrix4f().perspective((float) Math.toRadians(70), 16.0F / 9.0F, 0.05F, 512.0F);
        var values = new com.metallumextra.shader.IrisUniformValues();
        Map<String, double[]> one = values.frame(inputs(view, game, 0.0F, 6000, 0.0F, 1.0 / 60.0, 15));
        check("gbufferModelView times its inverse is the identity", sameMatrix(matrix(one, "gbufferModelView").mul(matrix(one, "gbufferModelViewInverse")), new org.joml.Matrix4f(), 1e-5F));
        check("gbufferProjection times its inverse is the identity", sameMatrix(matrix(one, "gbufferProjection").mul(matrix(one, "gbufferProjectionInverse")), new org.joml.Matrix4f(), 1e-4F));
        org.joml.Matrix4f projection = matrix(one, "gbufferProjection");
        org.joml.Vector4f nearPoint = projection.transform(new org.joml.Vector4f(0, 0, -0.05F, 1));
        org.joml.Vector4f farPoint = projection.transform(new org.joml.Vector4f(0, 0, -256.0F, 1));
        check("gbufferProjection puts the near plane (0.05) at depth -1 and the far plane (the render distance) at +1", Math.abs(nearPoint.z / nearPoint.w + 1) < 1e-4 && Math.abs(farPoint.z / farPoint.w - 1) < 1e-4);
        check("it keeps the game's field of view and aspect", projection.m00() == game.m00() && projection.m11() == game.m11());
        check("the camera, the sun at noon, shadowAngle, worldTime and the screen", one.get("cameraPosition")[0] == 100.5 && one.get("cameraPosition")[2] == -20.25
                && Math.abs(one.get("sunAngle")[0] - 0.25) < 1e-6 && one.get("worldTime")[0] == 6000 && one.get("viewWidth")[0] == 1600 && Math.abs(one.get("aspectRatio")[0] - 1600.0 / 900.0) < 1e-9
                && one.get("near")[0] == 0.05F && one.get("far")[0] == 256.0 && one.get("moonPhase")[0] == 3 && Math.abs(one.get("shadowAngle")[0] - 0.25) < 1e-6);
        org.joml.Vector3f sun = new org.joml.Vector3f((float) one.get("sunPosition")[0], (float) one.get("sunPosition")[1], (float) one.get("sunPosition")[2]);
        org.joml.Vector3f expectedSun = view.transformDirection(new org.joml.Vector3f(0, 100, 0));
        check("sunPosition at noon is straight up, 100 long, in view space", Math.abs(sun.length() - 100) < 1e-3 && sun.distance(expectedSun) < 1e-3);
        org.joml.Vector3f up = new org.joml.Vector3f((float) one.get("upPosition")[0], (float) one.get("upPosition")[1], (float) one.get("upPosition")[2]);
        check("upPosition is up in view space, 100 long", up.distance(expectedSun) < 1e-3);
        check("by day the shadow light is the sun, and the moon is opposite it", java.util.Arrays.equals(one.get("shadowLightPosition"), one.get("sunPosition"))
                && Math.abs(one.get("moonPosition")[0] + one.get("sunPosition")[0]) < 1e-3 && Math.abs(one.get("moonPosition")[1] + one.get("sunPosition")[1]) < 1e-3);
        check("eyeBrightness is the light levels times 16", one.get("eyeBrightness")[0] == 5 * 16 && one.get("eyeBrightness")[1] == 15 * 16);

        Map<String, double[]> two = values.frame(inputs(new org.joml.Matrix4f().rotateY(1.0F), game, (float) Math.PI, 18000 + 24000 * 3, 1.0F, 30.0, 0));
        check("the previous frame's view, projection and camera are kept", sameMatrix(matrix(two, "gbufferPreviousModelView"), view, 1e-6F) && sameMatrix(matrix(two, "gbufferPreviousProjection"), projection, 1e-6F)
                && two.get("previousCameraPosition")[0] == 100.5);
        check("at midnight sunAngle is 0.75, shadowAngle 0.25 and the shadow light the moon", Math.abs(two.get("sunAngle")[0] - 0.75) < 1e-5 && Math.abs(two.get("shadowAngle")[0] - 0.25) < 1e-5
                && java.util.Arrays.equals(two.get("shadowLightPosition"), two.get("moonPosition")));
        check("worldTime counts within the day and worldDay the days", two.get("worldTime")[0] == 18000 && two.get("worldDay")[0] == 3);
        check("wetness is rain smoothed with a half-life of 600 ticks: 30 s later it is halfway", Math.abs(two.get("wetness")[0] - 0.5) < 1e-9);
        check("eyeBrightnessSmooth moves towards the new light with a half-life of 10 ticks", two.get("eyeBrightnessSmooth")[1] < 5 && two.get("eyeBrightnessSmooth")[1] >= 0 && two.get("eyeBrightnessSmooth")[0] == 80);
        check("sunAngle: 0 at sunrise", Math.abs(com.metallumextra.shader.IrisUniformValues.irisSunAngle(1.5F * (float) Math.PI)) < 1e-5 || Math.abs(com.metallumextra.shader.IrisUniformValues.irisSunAngle(1.5F * (float) Math.PI) - 1) < 1e-5);

        // Depth: the game's (any projection of its kind) to OpenGL depth.
        for (float[] ab : new float[][] {{0.0F, 0.05F}, {-1.0000002F, -0.1F}, {0.05F, 0.2F}}) {
            org.joml.Matrix4f gameMatrix = new org.joml.Matrix4f().m00(1).m11(1).m22(ab[0]).m32(ab[1]).m23(-1).m33(0);
            boolean ok = true;
            for (double distance : new double[] {0.05, 0.5, 3, 10, 100, 255}) {
                double gameDepth = -ab[0] + ab[1] / distance;
                double expected = 0.5 + 0.5 * ((256 + 0.05) / (256 - 0.05) - 2 * 256 * 0.05 / ((256 - 0.05) * distance));
                ok &= Math.abs(com.metallumextra.shader.IrisUniformValues.glDepth(gameDepth, gameMatrix, 256.0F) - expected) < 1e-6;
            }
            check("OpenGL depth from the game's depth, for a projection with A=" + ab[0] + " B=" + ab[1], ok);
        }
        double nearDepth = com.metallumextra.shader.IrisUniformValues.glDepth(0.05 / 0.05, new org.joml.Matrix4f().m22(0).m32(0.05F).m23(-1).m33(0), 256.0F);
        double farDepth = com.metallumextra.shader.IrisUniformValues.glDepth(0.05 / 256.0, new org.joml.Matrix4f().m22(0).m32(0.05F).m23(-1).m33(0), 256.0F);
        check("depth is 0 at the near plane and 1 at the far plane", Math.abs(nearDepth) < 1e-6 && Math.abs(farDepth - 1) < 1e-6);

        // Written into a block.
        IrisUniforms.Layout block = IrisUniforms.layout();
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocateDirect(block.size()).order(java.nio.ByteOrder.nativeOrder());
        com.metallumextra.shader.IrisUniformValues.write(block, one, bytes);
        check("values land where the layout says, ints as ints", bytes.getFloat(block.offsets().get("cameraPosition")) == 100.5F && bytes.getFloat(block.offsets().get("cameraPosition") + 8) == -20.25F
                && bytes.getInt(block.offsets().get("worldTime")) == 6000 && bytes.getInt(block.offsets().get("eyeBrightness") + 4) == 240 && bytes.getFloat(block.offsets().get("frameTimeCounter")) == 12.5F);
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
        missing.remove("final.fsh");
        TestPacks.write(folder.resolve("Incomplete.zip"), null, missing);
        // A shader pack in the Iris layout that this version cannot run: listed, with the reason.
        TestPacks.write(folder.resolve("SomeIrisPack.zip"), null, Map.of("world0/gbuffers_skytextured.fsh", "void main() {}", "world0/gbuffers_skytextured.vsh", "void main() {}"));
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
        check("an incomplete pack is listed with an error and cannot be chosen", incomplete != null && !incomplete.selectable() && incomplete.error().contains("final.vsh"));
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
