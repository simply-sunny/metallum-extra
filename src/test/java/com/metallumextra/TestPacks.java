package com.metallumextra;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the test shader packs from the built-in shader files. Each is complete and stands alone; only the finished
 * world image's last pass ({@code program/edges.fsh}) differs.
 */
public final class TestPacks {
    public static final Path BUILTIN_SHADERS = Path.of("src/main/resources/assets/metallum-extra/shaders");

    /** Red areas of the picture become blue. */
    private static final String RED_TO_BLUE = recolor("color.r > 0.5 && color.r > color.g * 1.5 && color.r > color.b * 1.5", "vec3(0.0, 0.0, color.r)");
    /** Blue areas of the picture become red. */
    private static final String BLUE_TO_RED = recolor("color.b > 0.5 && color.b > color.r * 1.5 && color.b > color.g * 1.5", "vec3(color.b, 0.0, 0.0)");

    private TestPacks() {
    }

    private static String recolor(final String condition, final String replacement) {
        return """
                #version 330

                uniform sampler2D InSampler;

                in vec2 texCoord;
                out vec4 fragColor;

                void main() {
                    vec4 color = texture(InSampler, texCoord);

                    if (%s) {
                        color.rgb = %s;
                    }

                    fragColor = color;
                }
                """.formatted(condition, replacement);
    }

    /** Every file of the built-in pack, by its path inside the pack's {@code shaders/} folder. */
    public static Map<String, String> builtinFiles() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(BUILTIN_SHADERS)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                files.put(BUILTIN_SHADERS.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return files;
    }

    /** A pack with {@code pack.json} and these files. */
    public static void write(final Path zip, final String packJson, final Map<String, String> files) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            if (packJson != null) add(zos, "pack.json", packJson);
            for (Map.Entry<String, String> file : files.entrySet()) {
                add(zos, "shaders/" + file.getKey(), file.getValue());
            }
        }
    }

    public static void writeBuiltinCopy(final Path zip) throws IOException {
        write(zip, "{\"format\": 1}", builtinFiles());
    }

    /** Writes RedToBlue.zip, BlueToRed.zip and two broken packs into the folder. */
    public static void writeColorPacks(final Path folder) throws IOException {
        Map<String, String> red = builtinFiles();
        red.put("program/edges.fsh", RED_TO_BLUE);
        write(folder.resolve("RedToBlue.zip"), "{\"format\": 1}", red);
        Map<String, String> blue = builtinFiles();
        blue.put("program/edges.fsh", BLUE_TO_RED);
        write(folder.resolve("BlueToRed.zip"), "{\"format\": 1}", blue);
        // A pack with an option: red becomes blue only while SWAP is On.
        Map<String, String> optional = builtinFiles();
        optional.put("program/edges.fsh", RED_TO_BLUE.replace("if (color.r", "if (OPTION_SWAP == 1 && color.r"));
        write(folder.resolve("WithOption.zip"), "{\"format\": 1, \"options\": [{\"id\": \"SWAP\", \"name\": \"Swap\", \"type\": \"toggle\"}]}", optional);
        // Packs that must not take the game down: one whose GLSL has a mistake, and one that lacks a shader.
        Map<String, String> syntax = builtinFiles();
        syntax.put("program/edges.fsh", "#version 330\n\nuniform sampler2D InSampler;\nin vec2 texCoord;\nout vec4 fragColor;\n\nvoid main() {\n    fragColor = texture(InSampler, texCoord)\n}\n");
        write(folder.resolve("SyntaxError.zip"), "{\"format\": 1}", syntax);
        Map<String, String> incomplete = builtinFiles();
        incomplete.remove("program/sky.fsh");
        write(folder.resolve("Incomplete.zip"), "{\"format\": 1}", incomplete);
    }

    private static void add(final ZipOutputStream zos, final String name, final String text) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** {@code gradlew writeTestPacks}: puts the two color packs in the folder given. */
    public static void main(final String[] args) throws IOException {
        writeColorPacks(Path.of(args[0]));
    }
}
