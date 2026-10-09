package com.metallumextra;

import com.metallumextra.shader.pack.IrisUniforms;

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
 * world image's last program ({@code final.fsh}) differs.
 */
public final class TestPacks {
    public static final Path BUILTIN_SHADERS = Path.of("src/main/resources/assets/metallum-extra/pack/shaders");

    /** Red areas of the picture become blue. */
    private static final String RED_TO_BLUE = recolor("color.r > 0.5 && color.r > color.g * 1.5 && color.r > color.b * 1.5", "vec3(0.0, 0.0, color.r)");
    /** Blue areas of the picture become red. */
    private static final String BLUE_TO_RED = recolor("color.b > 0.5 && color.b > color.r * 1.5 && color.b > color.g * 1.5", "vec3(color.b, 0.0, 0.0)");

    private TestPacks() {
    }

    /** A final program that recolors, in place of the built-in pack's (which smooths edges). */
    private static String recolor(final String condition, final String replacement) {
        return """
                #version 330

                uniform sampler2D colortex0;

                in vec2 texcoord;
                layout(location = 0) out vec4 fragColor;

                void main() {
                    vec4 color = texture(colortex0, texcoord);

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
        write(zip, null, builtinFiles());
    }

    /** Writes the recoloring packs, the packs of the tests and two broken packs into the folder. */
    public static void writeColorPacks(final Path folder) throws IOException {
        Map<String, String> red = builtinFiles();
        red.put("final.fsh", RED_TO_BLUE);
        write(folder.resolve("RedToBlue.zip"), null, red);
        Map<String, String> blue = builtinFiles();
        blue.put("final.fsh", BLUE_TO_RED);
        write(folder.resolve("BlueToRed.zip"), null, blue);
        // A pack with an option: red becomes blue only while SWAP is on.
        Map<String, String> optional = builtinFiles();
        optional.put("final.fsh", RED_TO_BLUE.replace("uniform sampler2D colortex0;", "uniform sampler2D colortex0;\n\n//#define SWAP").replace("if (color.r", "#ifdef SWAP\n    if (color.r").replace("    fragColor = color;", "    fragColor = color;\n#endif"));
        write(folder.resolve("WithOption.zip"), null, optional);
        // Packs in the Iris layout: the whole image one color, and red and blue swapped.
        writeStandardPack(folder.resolve("SolidBlue.zip"), "fragColor = vec4(0.0, 0.0, 1.0, 1.0);");
        writeStandardPack(folder.resolve("SolidRed.zip"), "fragColor = vec4(1.0, 0.0, 0.0, 1.0);");
        writeStandardPack(folder.resolve("SwapRedBlue.zip"), "fragColor = vec4(color.bgr, 1.0);");
        writeIrisPacks(folder);
        writeUniformPacks(folder);
        writeWorldPack(folder);
        writeShadowPack(folder);
        // Packs that must not take the game down: one whose GLSL has a mistake, and one that lacks a shader.
        Map<String, String> syntax = builtinFiles();
        syntax.put("final.fsh", "#version 330\n\nuniform sampler2D colortex0;\nin vec2 texcoord;\nout vec4 fragColor;\n\nvoid main() {\n    fragColor = texture(colortex0, texcoord)\n}\n");
        write(folder.resolve("SyntaxError.zip"), null, syntax);
        Map<String, String> incomplete = builtinFiles();
        incomplete.remove("final.fsh");
        write(folder.resolve("Incomplete.zip"), null, incomplete);
    }

    /** The vertex shader of a full-screen program: one triangle that covers the screen. */
    private static final String FULLSCREEN_VSH = """
            #version 330

            out vec2 texcoord;

            void main() {
                vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                texcoord = uv;
            }
            """;

    /** A final program with this body, reading the world image as {@code colortex0}. */
    public static String finalFragment(final String body) {
        return "#version 330\n\nuniform sampler2D colortex0;\n\nin vec2 texcoord;\nout vec4 fragColor;\n\nvoid main() {\n    vec4 color = texture(colortex0, texcoord);\n    " + body + "\n}\n";
    }

    /** A pack in the Iris layout (no pack.json) with a {@code world0/final} program of this body. */
    public static void writeStandardPack(final Path zip, final String body) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("world0/final.vsh", FULLSCREEN_VSH);
        files.put("world0/final.fsh", finalFragment(body));
        files.put("shaders.properties", "# nothing to configure\n");
        writeStandard(zip, files);
    }

    /** A ZIP with only a {@code shaders/} folder of these files. */
    public static void writeStandard(final Path zip, final Map<String, String> files) throws IOException {
        write(zip, null, files);
    }

    // ---- packs for the multi-pass pipeline: solid colors and exact arithmetic, so every result is predictable ----

    /** A composite-style fragment program: the samplers it reads, the buffers it writes, and a body that sets {@code color} and {@code color1}. */
    private static String program(final String samplers, final String targets, final String body) {
        StringBuilder out = new StringBuilder("#version 330\n\n");
        for (String sampler : samplers.isEmpty() ? new String[0] : samplers.split(",")) out.append("uniform sampler2D ").append(sampler.strip()).append(";\n");
        out.append("\nin vec2 texcoord;\n");
        if (targets != null) out.append("/* RENDERTARGETS: ").append(targets).append(" */\n");
        int outputs = targets == null ? 1 : targets.split(",").length;
        for (int i = 0; i < outputs; i++) out.append("layout(location = ").append(i).append(") out vec4 ").append(i == 0 ? "color" : "color" + i).append(";\n");
        out.append("\nvoid main() {\n    ").append(body).append("\n}\n");
        return out.toString();
    }

    private static final String COPY = "color = texture(colortex0, texcoord);";

    /** A pack in the Iris layout whose programs are these (name without extension to fragment source), each with the full-screen vertex shader. */
    private static void irisPack(final Path folder, final String name, final Map<String, String> programs, final String properties) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> program : programs.entrySet()) {
            files.put("world0/" + program.getKey() + ".vsh", FULLSCREEN_VSH);
            files.put("world0/" + program.getKey() + ".fsh", program.getValue());
        }
        if (properties != null) files.put("shaders.properties", properties);
        writeStandard(folder.resolve(name + ".zip"), files);
    }

    private static Map<String, String> chain(final String... entries) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put(entries[i], entries[i + 1]);
        return result;
    }

    private static final String RED = "color = vec4(1.0, 0.0, 0.0, 1.0);";
    private static final String FINAL_COPY = program("colortex0", null, COPY);

    /** The packs the pipeline test (src/test/iris-pipeline) plays; each name says what it checks. */
    public static void writeIrisPacks(final Path folder) throws IOException {
        irisPack(folder, "IrisSingle", chain("composite", program("", "0", RED), "final", FINAL_COPY), null);
        irisPack(folder, "IrisTwoTargets", chain(
                "composite", program("", "0,1", "color = vec4(1.0, 0.0, 0.0, 1.0); color1 = vec4(0.0, 0.0, 1.0, 1.0);"),
                "final", program("colortex0, colortex1", null, "color = texcoord.x < 0.5 ? texture(colortex0, texcoord) : texture(colortex1, texcoord);")), null);
        irisPack(folder, "IrisIndependent", chain(
                "composite", program("", "0,1", "color = vec4(1.0, 0.0, 0.0, 1.0); color1 = vec4(0.0, 0.0, 1.0, 1.0);"),
                "composite1", program("", "0", "color = vec4(0.0, 1.0, 0.0, 1.0);"),
                "final", program("colortex0, colortex1", null, "color = texcoord.x < 0.5 ? texture(colortex0, texcoord) : texture(colortex1, texcoord);")), null);
        irisPack(folder, "IrisFlipOnce", chain(
                "composite", program("", "0", RED),
                "composite1", program("colortex0", "0", "color = vec4(0.0, 0.0, texture(colortex0, texcoord).r, 1.0);"),
                "final", FINAL_COPY), null);
        irisPack(folder, "IrisFlipTwice", chain(
                "composite", program("", "0", RED),
                "composite1", program("colortex0", "0", "color = vec4(0.0, texture(colortex0, texcoord).r, 0.0, 1.0);"),
                "composite2", program("colortex0", "0", "color = vec4(0.0, 0.0, texture(colortex0, texcoord).g, 1.0);"),
                "final", FINAL_COPY), null);
        irisPack(folder, "IrisNoFlip", chain(
                "composite", program("", "0", RED),
                "composite1", program("colortex0", "0", "color = vec4(0.0, 0.0, texture(colortex0, texcoord).r, 1.0);"),
                "final", FINAL_COPY), "flip.composite.colortex0=false\n");
        irisPack(folder, "IrisPersist", chain(
                "composite", program("colortex1, colortex2", "1,2",
                        "const bool colortex1Clear = false;\n    float a = texelFetch(colortex1, ivec2(gl_FragCoord.xy), 0).r; float b = texelFetch(colortex2, ivec2(gl_FragCoord.xy), 0).r;\n"
                                + "    // A counter that adds 16 (out of 255) every frame and wraps, so it can be followed across frames.\n"
                                + "    color = vec4(mod(floor(a * 255.0 + 0.5) + 16.0, 256.0) / 255.0, 0.0, 0.0, 1.0); color1 = vec4(mod(floor(b * 255.0 + 0.5) + 16.0, 256.0) / 255.0, 0.0, 0.0, 1.0);"),
                "final", FINAL_COPY), "\n");
        irisPack(folder, "IrisOrder", chain(
                "composite10", program("colortex0", "0", "color = vec4(0.0, 0.0, texture(colortex0, texcoord).g, 1.0);"),
                "composite", program("", "0", RED),
                "composite2", program("colortex0", "0", "color = vec4(0.0, texture(colortex0, texcoord).r, 0.0, 1.0);"),
                "final", FINAL_COPY), null);
        irisPack(folder, "IrisDisabled", chain(
                "composite", program("", "0", RED),
                "composite1", program("", "0", "color = vec4(0.0, 1.0, 0.0, 1.0);"),
                "composite2", program("colortex0", "0", "color = vec4(0.0, 0.0, texture(colortex0, texcoord).r, 1.0);"),
                "final", FINAL_COPY), "program.composite1.enabled=false\n");
        irisPack(folder, "IrisNoFinal", chain(
                "composite", program("", "0", RED),
                "composite2", program("colortex0", "0", "color = vec4(0.0, 0.0, texture(colortex0, texcoord).r, 1.0);")), null);
        // The final image shows where the depth textures differ: red where depthtex0 and depthtex1 do, green where depthtex1 and depthtex2 do.
        irisPack(folder, "IrisDepth", chain(
                "final", program("depthtex0, depthtex1, depthtex2", null,
                        "ivec2 at = ivec2(gl_FragCoord.xy);\n    float d0 = texelFetch(depthtex0, at, 0).r; float d1 = texelFetch(depthtex1, at, 0).r; float d2 = texelFetch(depthtex2, at, 0).r;\n"
                                + "    color = vec4(min(abs(d0 - d1) * 400.0, 1.0), min(abs(d1 - d2) * 400.0, 1.0), 0.0, 1.0);")), null);
        irisPack(folder, "IrisReadsOne", chain("final", program("colortex1", null, "color = texture(colortex1, texcoord);")), null);
        irisPack(folder, "IrisStages", chain(
                "begin", program("", "2", "color = vec4(1.0, 0.0, 0.0, 1.0);"),
                "prepare", program("colortex2", "2", "color = vec4(texture(colortex2, texcoord).r, 1.0, 0.0, 1.0);"),
                "deferred", program("colortex2", "0", "color = vec4(0.0, texture(colortex2, texcoord).g, 0.0, 1.0);"),
                "final", FINAL_COPY), null);
        irisPack(folder, "IrisBroken", chain(
                "composite", "#version 330\nin vec2 texcoord;\nlayout(location = 0) out vec4 color;\nvoid main() {\n    color = vec4(1.0)\n}\n",
                "final", FINAL_COPY), null);
    }

    /**
     * Writes every standard uniform into the top row of colortex1 (RGBA32F), in the order of {@link IrisUniforms#ALL}: a matrix takes
     * four pixels (its columns), anything else one. The test reads the row back and compares it with what the game computed.
     */
    /** The dump writes one pixel per uniform into a row of 64; the shadow matrices and the built-in shader's own uniforms have a test of their own. */
    static boolean skippedInDump(final String name) {
        return name.startsWith("Mx") || name.startsWith("shadowModelView") || name.startsWith("shadowProjection");
    }

    private static String uniformDump() {
        StringBuilder declarations = new StringBuilder();
        StringBuilder cases = new StringBuilder();
        int pixel = 0;
        for (IrisUniforms.Uniform uniform : IrisUniforms.ALL) {
            if (uniform.gbuffers() || skippedInDump(uniform.name())) continue;
            declarations.append("uniform ").append(uniform.type()).append(' ').append(uniform.name()).append(";\n");
            switch (uniform.type()) {
                case "mat4" -> {
                    for (int column = 0; column < 4; column++) cases.append("    if (i == ").append(pixel++).append(") v = ").append(uniform.name()).append('[').append(column).append("];\n");
                }
                case "vec3" -> cases.append("    if (i == ").append(pixel++).append(") v = vec4(").append(uniform.name()).append(", 0.0);\n");
                case "ivec2" -> cases.append("    if (i == ").append(pixel++).append(") v = vec4(vec2(").append(uniform.name()).append("), 0.0, 0.0);\n");
                default -> cases.append("    if (i == ").append(pixel++).append(") v = vec4(float(").append(uniform.name()).append("), 0.0, 0.0, 0.0);\n");
            }
        }
        return "#version 330\n\n" + declarations + "\nconst int colortex1Format = RGBA32F;\nin vec2 texcoord;\n/* RENDERTARGETS: 1 */\nlayout(location = 0) out vec4 color;\n\n"
                + "void main() {\n    int i = int(gl_FragCoord.x);\n    vec4 v = vec4(0.0);\n" + cases + "    color = gl_FragCoord.y < 1.0 ? v : vec4(0.0);\n}\n";
    }

    /** The packs that test the standard uniforms: every one written out for comparison, and the view-space distance rebuilt from depth. */
    public static void writeUniformPacks(final Path folder) throws IOException {
        irisPack(folder, "IrisUniforms", chain("composite", uniformDump(), "final", FINAL_COPY), null);
        irisPack(folder, "IrisViewDepth", chain("composite", viewDepthComposite(1), "final", FINAL_COPY), null);
        // Terrain drawn by the pack's own gbuffers_terrain, into three buffers: color, the quad's normal as the program saw it (vaNormal),
        // and the light (vaUV2). The final image shows the three side by side, and a composite rebuilds the wall's distance from depth.
        Map<String, String> terrain = new java.util.LinkedHashMap<>();
        terrain.put("gbuffers_terrain.vsh", "#version 330 core\n\nin vec3 vaPosition;\nin vec4 vaColor;\nin vec2 vaUV0;\nin ivec2 vaUV2;\nin vec3 vaNormal;\nin vec2 mc_Entity;\n"
                + "uniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform vec3 chunkOffset;\n\nout vec2 uv;\nout vec4 tint;\nout vec2 light;\nout vec3 facing;\n\n"
                + "void main() {\n    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0);\n    uv = vaUV0;\n    tint = vaColor;\n    light = vec2(vaUV2) / 240.0;\n    facing = vaNormal;\n}\n");
        terrain.put("gbuffers_terrain.fsh", "#version 330 core\n\nuniform sampler2D gtexture;\nuniform float alphaTestRef;\n\nin vec2 uv;\nin vec4 tint;\nin vec2 light;\nin vec3 facing;\n\n"
                + "/* RENDERTARGETS: 0,1,2 */\nlayout(location = 0) out vec4 outColor;\nlayout(location = 1) out vec4 outNormal;\nlayout(location = 2) out vec4 outLight;\n\n"
                + "void main() {\n    vec4 albedo = texture(gtexture, uv) * tint;\n    if (albedo.a < alphaTestRef) discard;\n    outColor = vec4(1.0, 0.0, 1.0, albedo.a);\n"
                + "    outNormal = vec4(facing * 0.5 + 0.5, 1.0);\n    outLight = vec4(light, 0.0, 1.0);\n}\n");
        terrain.put("composite.vsh", FULLSCREEN_VSH);
        terrain.put("composite.fsh", viewDepthComposite(3));
        terrain.put("final.vsh", FULLSCREEN_VSH);
        terrain.put("final.fsh", program("colortex0, colortex1, colortex2", null,
                "vec2 uv = vec2(fract(texcoord.x * 3.0), texcoord.y);\n    color = texcoord.x < 1.0 / 3.0 ? texture(colortex0, uv) : texcoord.x < 2.0 / 3.0 ? texture(colortex1, uv) : texture(colortex2, uv);"));
        Map<String, String> placed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> file : terrain.entrySet()) placed.put("world0/" + file.getKey(), file.getValue());
        writeStandard(folder.resolve("IrisTerrain.zip"), placed);
    }

    /** A composite that rebuilds each pixel's view-space distance from depth into the given buffer (RGBA32F): along the view axis from depthtex1, along the ray, and from depthtex0. */
    private static String viewDepthComposite(final int buffer) {
        return "#version 330\n\nuniform sampler2D depthtex0;\nuniform sampler2D depthtex1;\nuniform mat4 gbufferProjectionInverse;\n\nconst int colortex" + buffer + "Format = RGBA32F;\nin vec2 texcoord;\n"
                + "/* RENDERTARGETS: " + buffer + " */\nlayout(location = 0) out vec4 color;\n\n"
                + "void main() {\n    // The middle row of the picture, whatever column this pixel is in the top row of the output (row 0 is the bottom of the screen).\n"
                + "    ivec2 at = ivec2(int(gl_FragCoord.x), textureSize(depthtex1, 0).y / 2);\n    vec2 uv = (vec2(at) + 0.5) / vec2(textureSize(depthtex1, 0));\n"
                + "    vec4 a = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, texelFetch(depthtex1, at, 0).r * 2.0 - 1.0, 1.0);\n"
                + "    vec4 b = gbufferProjectionInverse * vec4(uv * 2.0 - 1.0, texelFetch(depthtex0, at, 0).r * 2.0 - 1.0, 1.0);\n"
                + "    color = vec4(-a.z / a.w, length(a.xyz / a.w), -b.z / b.w, 1.0);\n}\n";
    }

    /** A program that draws the world and paints everything it draws one color, into colortex0 and colortex1 (so the color says which program drew it). */
    private static Map<String, String> worldProgram(final String name, final String color) {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("world0/" + name + ".vsh", "#version 330 core\n\nin vec3 vaPosition;\nin vec2 vaUV0;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform vec3 chunkOffset;\n\nout vec2 uv;\n\n"
                + "void main() {\n    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0);\n    uv = vaUV0;\n}\n");
        files.put("world0/" + name + ".fsh", "#version 330 core\n\nuniform sampler2D gtexture;\nuniform float alphaTestRef;\n\nin vec2 uv;\n\n/* RENDERTARGETS: 0,1 */\n"
                + "layout(location = 0) out vec4 outColor;\nlayout(location = 1) out vec4 outId;\n\nvoid main() {\n    vec4 albedo = texture(gtexture, uv);\n    if (albedo.a < max(alphaTestRef, 0.1)) discard;\n"
                + "    outColor = vec4(" + color + ", 1.0);\n    outId = vec4(" + color + ", 1.0);\n}\n");
        return files;
    }

    /**
     * Terrain magenta, entities and items green, blocks the game draws one at a time blue, particles yellow, rain and snow cyan; and a final image with
     * colortex0 on the left and colortex1 on the right. The game's own sky and anything else the pack has no program for is left as it is in colortex0
     * and white (colortex1's clear color) in colortex1.
     */
    public static void writeWorldPack(final Path folder) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.putAll(worldProgram("gbuffers_terrain", "1.0, 0.0, 1.0"));
        files.putAll(worldProgram("gbuffers_entities", "0.0, 1.0, 0.0"));
        files.putAll(worldProgram("gbuffers_block", "0.0, 0.0, 1.0"));
        files.putAll(worldProgram("gbuffers_particles", "1.0, 1.0, 0.0"));
        files.putAll(worldProgram("gbuffers_weather", "0.0, 1.0, 1.0"));
        files.put("world0/final.vsh", FULLSCREEN_VSH);
        files.put("world0/final.fsh", program("colortex0, colortex1", null,
                "vec2 uv = vec2(fract(texcoord.x * 2.0), texcoord.y);\n    color = texcoord.x < 0.5 ? texture(colortex0, uv) : texture(colortex1, uv);"));
        writeStandard(folder.resolve("IrisWorld.zip"), files);
    }

    /**
     * Terrain tests itself against the shadow map: green where the sun reaches it and red where something is in the way, in colortex0; colortex1
     * holds what the shadow map's color buffer has at the same place (blue wherever the shadow program drew). The final image shows the two side by side.
     */
    public static void writeShadowPack(final Path folder) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("world0/shadow.vsh", "#version 330 core\n\nin vec3 vaPosition;\nin vec2 vaUV0;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform vec3 chunkOffset;\n\nout vec2 uv;\n\n"
                + "void main() {\n    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0);\n    uv = vaUV0;\n}\n");
        files.put("world0/shadow.fsh", "#version 330 core\n\nuniform sampler2D gtexture;\n\nin vec2 uv;\n\nlayout(location = 0) out vec4 shadowColor;\n\n"
                + "const int shadowMapResolution = 2048;\nconst float shadowDistance = 96.0;\nconst float sunPathRotation = 15.0;\n\n"
                + "void main() {\n    if (texture(gtexture, uv).a < 0.1) discard;\n    shadowColor = vec4(0.0, 0.0, 1.0, 1.0);\n}\n");
        files.put("world0/gbuffers_terrain.vsh", "#version 330 core\n\nin vec3 vaPosition;\nin vec2 vaUV0;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform vec3 chunkOffset;\n\n"
                + "out vec2 uv;\nout vec3 position;\n\nvoid main() {\n    position = vaPosition + chunkOffset;\n    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);\n    uv = vaUV0;\n}\n");
        files.put("world0/gbuffers_terrain.fsh", "#version 330 core\n\nuniform sampler2D gtexture;\nuniform sampler2D shadowtex0;\nuniform sampler2D shadowcolor0;\n"
                + "uniform mat4 shadowModelView;\nuniform mat4 shadowProjection;\nuniform float alphaTestRef;\n\nin vec2 uv;\nin vec3 position;\n\n/* RENDERTARGETS: 0,1 */\n"
                + "layout(location = 0) out vec4 outColor;\nlayout(location = 1) out vec4 outShadowColor;\n\n"
                + "void main() {\n    if (texture(gtexture, uv).a < max(alphaTestRef, 0.1)) discard;\n    vec4 clip = shadowProjection * shadowModelView * vec4(position, 1.0);\n"
                + "    vec3 ndc = clip.xyz / clip.w;\n    vec2 at = ndc.xy * 0.5 + 0.5;\n    float depth = ndc.z * 0.5 + 0.5;\n    float stored = texture(shadowtex0, at).r;\n"
                + "    outColor = depth - 0.003 <= stored ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);\n    outShadowColor = texture(shadowcolor0, at);\n}\n");
        files.put("world0/final.vsh", FULLSCREEN_VSH);
        files.put("world0/final.fsh", program("colortex0, colortex1", null,
                "vec2 uv = vec2(fract(texcoord.x * 2.0), texcoord.y);\n    color = texcoord.x < 0.5 ? texture(colortex0, uv) : texture(colortex1, uv);"));
        writeStandard(folder.resolve("IrisShadow.zip"), files);
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
