package com.metallumextra.shader.pack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a program written for the compatibility profile of GLSL (OptiFine's classic packs: {@code #version 120} to {@code 150}, {@code gl_Vertex},
 * {@code ftransform()}, {@code gl_FragData}, {@code texture2D} ...) into the Iris names the rest of the loader reads ({@code vaPosition},
 * {@code modelViewMatrix}, {@code outColor0}, {@code texture} ...), the way Iris's own patcher does.
 * <p>
 * The rewrite is one identifier at a time, never inside a comment, and never changes a name it does not know: it looks at the words of the
 * code, not at the structure of its preprocessor conditions, so it gives the same answer whichever branches the options leave in. What it
 * cannot express is refused by name (see {@link #untranslatable}), not left for the compiler to fail on.
 * <p>
 * Which program it is for matters in two ways. A vertex stage reads what the stage provides, so {@link Kind#FULLSCREEN} programs get the screen's
 * quad (made from {@code gl_VertexIndex}, with the identity model-view and a projection that takes 0..1 to the screen) and {@link Kind#WORLD} programs
 * get the world's vertices ({@code vaPosition + chunkOffset}, the game's matrices). And {@code varying} is an output of the vertex stage and an input
 * of the fragment stage.
 */
public final class LegacyGlsl {
    /** What the program draws, which decides what its vertex inputs and matrices are. */
    public enum Kind {
        /** composite, deferred, final ...: a quad over the screen. */
        FULLSCREEN,
        /** gbuffers_*, shadow: geometry of the world. */
        WORLD;

        /** The kind of the program in this file: {@code world0/gbuffers_terrain.vsh} is {@link #WORLD}, {@code composite2.fsh} is {@link #FULLSCREEN}. */
        public static Kind of(final String path) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.startsWith("gbuffers_") || name.startsWith("dh_") || (name.startsWith("shadow") && !name.startsWith("shadowcomp")) ? WORLD : FULLSCREEN;
        }
    }

    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)(?:[ \\t]+(\\w+))?[ \\t]*$");
    private static final Pattern HEADER_LINE = Pattern.compile("(?m)^[ \\t]*(#[ \\t]*version[^\\n]*|#[ \\t]*extension[^\\n]*)$");
    private static final Pattern WORD = Pattern.compile("\\b(?:gl_Fog\\s*\\.\\s*[a-z]+|gl_[A-Za-z0-9_]+|ftransform|texture2D|texture2DLod|texture2DGrad|texture2DGradARB|texture2DLodARB|texture2DProj|texture3D|texture3DLod|shadow2D|varying|attribute)\\b");
    private static final Pattern FRAG_DATA = Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern TEXTURE_MATRIX = Pattern.compile("gl_TextureMatrix\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern ATTRIBUTE = Pattern.compile("(?m)^([ \\t]*)(?:layout\\s*\\([^)]*\\)\\s*)?(?:attribute|in)\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*;[ \\t]*$");
    /** The inputs an Iris program may declare, with the type the loader expects them to have. A legacy program often declares another ({@code vec4 mc_Entity}). */
    private static final java.util.Map<String, String> CANONICAL = java.util.Map.of("mc_Entity", "vec2", "mc_midTexCoord", "vec2", "at_tangent", "vec4", "at_midBlock", "vec4");

    /** The names of the compatibility profile that stay as they are: they exist in the core profile too. */
    private static final Pattern KEEP = Pattern.compile("gl_(Position|FragCoord|VertexID|VertexIndex|InstanceID|FragDepth|FrontFacing|PointSize|PointCoord|ClipDistance|PrimitiveID|Layer|ViewportIndex|SampleID|SamplePosition|SampleMask|"
            + "GlobalInvocationID|LocalInvocationID|LocalInvocationIndex|WorkGroupID|WorkGroupSize|NumWorkGroups)");

    private LegacyGlsl() {
    }

    /** Whether the program is written for the compatibility profile and has to be translated: an old version, or one of its names. */
    public static boolean isLegacy(final String source) {
        Matcher version = VERSION.matcher(source);
        if (version.find()) {
            int number = Integer.parseInt(version.group(1));
            if (number < 330 || "compatibility".equals(version.group(2))) return true;
        }
        Matcher word = WORD.matcher(code(source));
        while (word.find()) {
            String w = word.group();
            if (!w.startsWith("gl_") || !KEEP.matcher(w).matches()) return true;
        }
        return false;
    }

    /**
     * @param source the program with its includes and options in, for {@code vertex} stage or the fragment stage
     * @return the program in the Iris names; the source itself when it is not legacy
     * @throws PackException naming what the program uses that this version cannot translate
     */
    public static String translate(final String source, final boolean vertex, final Kind kind) throws PackException {
        if (!isLegacy(source)) return source;
        Set<String> uses = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder(source.length() + 1024);
        boolean[] fragmentOutputs = new boolean[16];
        scan(source, (start, end, isCode) -> {
            String text = source.substring(start, end);
            out.append(isCode ? rewrite(text, vertex, uses, fragmentOutputs) : text);
        });

        String result = out.toString();
        StringBuilder macros = new StringBuilder();
        StringBuilder declarations = new StringBuilder();
        result = renameInputs(result, vertex, macros);
        provide(uses, vertex, kind, fragmentOutputs, macros, declarations);
        result = VERSION.matcher(result).replaceFirst("#version 330 core");
        if (!VERSION.matcher(source).find()) result = "#version 330 core\n" + result;
        int at = 0;
        Matcher header = HEADER_LINE.matcher(result);
        while (header.find()) at = header.end();
        result = result.substring(0, at) + "\n" + macros + declarations + result.substring(at);
        untranslatable(result);
        return result;
    }

    /** Words that GLSL leaves free but C++ (and so Metal Shading Language) reserves: a pack may name a variable {@code new}. */
    private static final Pattern CPP_KEYWORDS = Pattern.compile("\\b(new|delete|operator|private|protected|friend|try|catch|throw|virtual|mutable|explicit|typename|export|and|or|not|xor|bitand|bitor|compl|nullptr|constexpr|decltype|alignas|alignof|wchar_t|char|signed|static_cast|dynamic_cast|reinterpret_cast|const_cast|thread_local)\\b");

    /** Every variable or function the pack names with a word of {@link #CPP_KEYWORDS} gets a prefix, in code only (never in a comment). */
    public static String cppKeywords(final String source) {
        if (!CPP_KEYWORDS.matcher(code(source)).find()) return source;
        StringBuilder out = new StringBuilder(source.length() + 64);
        scan(source, (start, end, isCode) -> {
            String part = source.substring(start, end);
            out.append(isCode ? CPP_KEYWORDS.matcher(part).replaceAll("mx_kw_$1") : part);
        });
        return out.toString();
    }

    /**
     * A shadow map read the way a {@code sampler2DShadow} reads it: the depth is compared with the reference (less than or equal) at the four texels
     * around the point, and the four results are blended by distance, which is what OpenGL's hardware filtering does. The map is bound as the plain
     * depth texture, since Metal's sampler here does not compare.
     */
    private static final String SHADOW_COMPARE = """
            float mx_shadowCompare(sampler2D s, vec3 p) {
                ivec2 size = textureSize(s, 0);
                vec2 t = p.xy * vec2(size) - 0.5;
                ivec2 i = ivec2(floor(t));
                vec2 f = fract(t);
                ivec2 hi = size - 1;
                float a = p.z <= texelFetch(s, clamp(i, ivec2(0), hi), 0).r ? 1.0 : 0.0;
                float b = p.z <= texelFetch(s, clamp(i + ivec2(1, 0), ivec2(0), hi), 0).r ? 1.0 : 0.0;
                float c = p.z <= texelFetch(s, clamp(i + ivec2(0, 1), ivec2(0), hi), 0).r ? 1.0 : 0.0;
                float d = p.z <= texelFetch(s, clamp(i + ivec2(1, 1), ivec2(0), hi), 0).r ? 1.0 : 0.0;
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }
            """;

    /**
     * A program that reads its shadow map as a {@code sampler2DShadow} gets it as a plain sampler and {@link #SHADOW_COMPARE}: the type is replaced
     * everywhere, and a {@code texture(shadowtex, vec3)} on a declared shadow sampler becomes the comparison.
     */
    public static String shadowSamplers(final String source) {
        String text = source;
        if (code(text).contains("sampler2DShadow")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            Matcher declared = Pattern.compile("\\buniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2DShadow\\s+([\\w\\s,]+);").matcher(code(text));
            while (declared.find()) for (String name : declared.group(1).split(",")) names.add(name.trim());
            text = text.replaceAll("\\bsampler2DShadow\\b", "sampler2D");
            for (String name : names) text = text.replaceAll("\\btexture\\s*\\(\\s*" + name + "\\s*,", "mx_shadowCompare(" + name + ",");
        }
        if (!code(text).contains("mx_shadowCompare") || code(text).contains("float mx_shadowCompare")) return text;
        Matcher header = HEADER_LINE.matcher(text);
        int at = 0;
        while (header.find()) at = header.end();
        return text.substring(0, at) + "\n" + SHADOW_COMPARE + text.substring(at);
    }

    /** The names a legacy program has no equivalent for are refused here, with the name. */
    private static void untranslatable(final String text) throws PackException {
        Matcher word = Pattern.compile("\\bgl_[A-Za-z0-9_]+").matcher(code(text));
        while (word.find()) {
            if (!KEEP.matcher(word.group()).matches() && !word.group().startsWith("gl_FragData")) {
                throw new PackException(word.group() + " is a name of the old compatibility profile of GLSL that this version cannot translate");
            }
        }
    }

    /** What one stretch of code uses of the old profile, written in the new names. */
    private static String rewrite(final String code, final boolean vertex, final Set<String> uses, final boolean[] outputs) {
        String text = code;
        Matcher frag = FRAG_DATA.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (frag.find()) {
            int n = Integer.parseInt(frag.group(1));
            if (n < outputs.length) outputs[n] = true;
            frag.appendReplacement(sb, "outColor" + n);
        }
        frag.appendTail(sb);
        text = sb.toString();
        Matcher matrix = TEXTURE_MATRIX.matcher(text);
        sb = new StringBuilder();
        while (matrix.find()) {
            int n = Integer.parseInt(matrix.group(1));
            uses.add(n == 0 ? "textureMatrix0" : n == 1 ? "textureMatrix1" : "textureMatrixOther");
            matrix.appendReplacement(sb, n <= 1 ? "iris_TextureMatrix" + n : "iris_TextureMatrixOther");
        }
        matrix.appendTail(sb);
        text = sb.toString();
        Matcher word = WORD.matcher(text);
        sb = new StringBuilder();
        while (word.find()) {
            String w = word.group().replaceAll("\\s+", "");
            String to = switch (w) {
                case "varying" -> vertex ? "out" : "in";
                case "attribute" -> "in";
                case "texture2D", "texture3D" -> "texture";
                case "texture2DLod", "texture3DLod", "texture2DLodARB" -> "textureLod";
                case "texture2DGrad", "texture2DGradARB" -> "textureGrad";
                case "texture2DProj" -> "textureProj";
                case "shadow2D" -> {
                    uses.add("shadow2D");
                    yield "iris_shadow2D";
                }
                case "ftransform" -> {
                    uses.add("ftransform");
                    yield "iris_ftransform";
                }
                case "gl_Vertex" -> use(uses, "Vertex", "iris_Vertex");
                case "gl_Normal" -> use(uses, "Normal", "iris_Normal");
                case "gl_Color" -> use(uses, "Color", "iris_Color");
                case "gl_MultiTexCoord0" -> use(uses, "MultiTexCoord0", "iris_MultiTexCoord0");
                case "gl_MultiTexCoord1" -> use(uses, "MultiTexCoord1", "iris_MultiTexCoord1");
                case "gl_ModelViewMatrix" -> use(uses, "ModelViewMatrix", "iris_ModelViewMatrix");
                case "gl_ProjectionMatrix" -> use(uses, "ProjectionMatrix", "iris_ProjectionMatrix");
                case "gl_ModelViewProjectionMatrix" -> {
                    uses.add("ModelViewMatrix");
                    uses.add("ProjectionMatrix");
                    yield "(iris_ProjectionMatrix * iris_ModelViewMatrix)";
                }
                case "gl_NormalMatrix" -> use(uses, "NormalMatrix", "iris_NormalMatrix");
                case "gl_ModelViewMatrixInverse" -> use(uses, "ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
                case "gl_ProjectionMatrixInverse" -> use(uses, "ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");
                case "gl_FragColor" -> {
                    outputs[0] = true;
                    yield "outColor0";
                }
                case "gl_Fog.start" -> use(uses, "FogStart", "fogStart");
                case "gl_Fog.end" -> use(uses, "FogEnd", "fogEnd");
                case "gl_Fog.scale" -> {
                    uses.add("FogStart");
                    uses.add("FogEnd");
                    yield "(1.0 / (fogEnd - fogStart))";
                }
                default -> w;
            };
            word.appendReplacement(sb, Matcher.quoteReplacement(to.equals(w) ? word.group() : to));
        }
        word.appendTail(sb);
        return sb.toString();
    }

    private static String use(final Set<String> uses, final String key, final String replacement) {
        uses.add(key);
        return replacement;
    }

    /**
     * A legacy program declares its extra vertex inputs with the type it likes ({@code attribute vec4 mc_Entity;}); the loader knows each of them
     * by one type. The declaration becomes the known one, and the program's own name becomes a macro that makes the type the program wants.
     */
    private static String renameInputs(final String text, final boolean vertex, final StringBuilder macros) {
        if (!vertex) return text;
        Matcher match = ATTRIBUTE.matcher(text);
        java.util.Map<String, String> renamed = new java.util.LinkedHashMap<>();
        while (match.find()) {
            String expected = CANONICAL.get(match.group(3));
            if (expected != null && !expected.equals(match.group(2))) renamed.put(match.group(3), match.group(2));
        }
        String result = text;
        for (java.util.Map.Entry<String, String> entry : renamed.entrySet()) {
            String name = entry.getKey();
            String expected = CANONICAL.get(name);
            // The declaration takes the type the loader knows, and every use goes through a macro that makes the type the program asked for.
            result = Pattern.compile("(?m)^([ \\t]*)(?:layout\\s*\\([^)]*\\)\\s*)?(?:attribute|in)\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$")
                    .matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "in " + expected + " " + name + ";"));
            result = Pattern.compile("(?<![\\w])" + name + "(?![\\w])(?!\\s*;\\s*$)", Pattern.MULTILINE).matcher(result).replaceAll("iris_legacy_" + name);
            macros.append("#define iris_legacy_").append(name).append(' ').append(widen(expected, entry.getValue(), name)).append('\n');
        }
        return result;
    }

    /** An expression of type {@code to} made from a value of type {@code from}: the missing components are 0, and the last one 1. */
    private static String widen(final String from, final String to, final String name) {
        int have = from.equals("vec2") ? 2 : from.equals("vec3") ? 3 : 4;
        int want = to.equals("vec2") ? 2 : to.equals("vec3") ? 3 : to.equals("vec4") ? 4 : 1;
        if (want <= have) return want == have ? name : name + "." + "xyzw".substring(0, want);
        StringBuilder fill = new StringBuilder("vec" + want + "(" + name);
        for (int i = have; i < want; i++) fill.append(", ").append(i == want - 1 && want == 4 ? "1.0" : "0.0");
        return fill.append(')').toString();
    }

    /** The declarations and macros the translated names need, for this stage and kind of program. */
    private static void provide(final Set<String> uses, final boolean vertex, final Kind kind, final boolean[] outputs, final StringBuilder macros, final StringBuilder declarations) {
        boolean world = kind == Kind.WORLD;
        boolean mvp = uses.contains("ftransform");
        if (mvp) {
            uses.add("ModelViewMatrix");
            uses.add("ProjectionMatrix");
            uses.add("Vertex");
        }
        if (uses.contains("Vertex")) {
            if (world) {
                if (vertex) declarations.append("in vec3 vaPosition;\n");
                macros.append("#define iris_Vertex vec4(vaPosition + chunkOffset, 1.0)\n");
                declarations.append("uniform vec3 chunkOffset;\n");
            } else {
                macros.append("#define iris_Vertex vec4(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2), 0.0, 1.0)\n");
            }
        }
        if (uses.contains("Normal")) {
            if (world) {
                if (vertex) declarations.append("in vec3 vaNormal;\n");
                macros.append("#define iris_Normal vaNormal\n");
            } else {
                macros.append("#define iris_Normal vec3(0.0, 0.0, 1.0)\n");
            }
        }
        if (uses.contains("Color")) {
            if (world) {
                if (vertex) declarations.append("in vec4 vaColor;\n");
                macros.append("#define iris_Color vaColor\n");
            } else {
                macros.append("#define iris_Color vec4(1.0)\n");
            }
        }
        if (uses.contains("MultiTexCoord0")) {
            if (world) {
                if (vertex) declarations.append("in vec2 vaUV0;\n");
                macros.append("#define iris_MultiTexCoord0 vec4(vaUV0, 0.0, 1.0)\n");
            } else {
                macros.append("#define iris_MultiTexCoord0 vec4(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2), 0.0, 1.0)\n");
            }
        }
        if (uses.contains("MultiTexCoord1")) {
            if (world) {
                if (vertex) declarations.append("in ivec2 vaUV2;\n");
                macros.append("#define iris_MultiTexCoord1 vec4(vec2(vaUV2), 0.0, 1.0)\n");
            } else {
                macros.append("#define iris_MultiTexCoord1 vec4(0.0, 0.0, 0.0, 1.0)\n");
            }
        }
        if (uses.contains("ModelViewMatrix")) {
            if (world) {
                declarations.append("uniform mat4 modelViewMatrix;\n");
                macros.append("#define iris_ModelViewMatrix modelViewMatrix\n");
            } else {
                macros.append("#define iris_ModelViewMatrix mat4(1.0)\n");
            }
        }
        if (uses.contains("ProjectionMatrix")) {
            if (world) {
                declarations.append("uniform mat4 projectionMatrix;\n");
                macros.append("#define iris_ProjectionMatrix projectionMatrix\n");
            } else {
                // The screen's quad runs from 0 to 1; this takes it to clip space, as OptiFine's orthographic projection did.
                macros.append("#define iris_ProjectionMatrix mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0)\n");
            }
        }
        if (uses.contains("NormalMatrix")) {
            if (world) {
                declarations.append("uniform mat3 normalMatrix;\n");
                macros.append("#define iris_NormalMatrix normalMatrix\n");
            } else {
                macros.append("#define iris_NormalMatrix mat3(1.0)\n");
            }
        }
        if (uses.contains("ModelViewMatrixInverse")) {
            if (world) {
                declarations.append("uniform mat4 modelViewMatrixInverse;\n");
                macros.append("#define iris_ModelViewMatrixInverse modelViewMatrixInverse\n");
            } else {
                macros.append("#define iris_ModelViewMatrixInverse mat4(1.0)\n");
            }
        }
        if (uses.contains("ProjectionMatrixInverse")) {
            if (world) {
                declarations.append("uniform mat4 projectionMatrixInverse;\n");
                macros.append("#define iris_ProjectionMatrixInverse projectionMatrixInverse\n");
            } else {
                macros.append("#define iris_ProjectionMatrixInverse mat4(0.5, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.5, 0.5, 0.0, 1.0)\n");
            }
        }
        if (uses.contains("ftransform")) macros.append("#define iris_ftransform() (iris_ProjectionMatrix * iris_ModelViewMatrix * iris_Vertex)\n");
        if (uses.contains("textureMatrix0")) macros.append("#define iris_TextureMatrix0 ").append(world ? "textureMatrix" : "mat4(1.0)").append('\n');
        if (uses.contains("textureMatrix0") && world) declarations.append("uniform mat4 textureMatrix;\n");
        // The lightmap's coordinates run 0..255 in the vertex; the matrix takes them to the 16x16 texture's texel centers.
        if (uses.contains("textureMatrix1")) macros.append("#define iris_TextureMatrix1 mat4(0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.03125, 0.03125, 0.03125, 1.0)\n");
        if (uses.contains("textureMatrixOther")) macros.append("#define iris_TextureMatrixOther mat4(1.0)\n");
        if (uses.contains("FogStart")) declarations.append("uniform float fogStart;\n");
        if (uses.contains("FogEnd")) declarations.append("uniform float fogEnd;\n");
        if (uses.contains("shadow2D")) {
            macros.append("#define iris_shadow2D(s, p) vec4(vec3(mx_shadowCompare((s), (p))), 1.0)\n");
        }
        if (!vertex) {
            for (int i = 0; i < outputs.length; i++) {
                if (outputs[i]) declarations.append("layout(location = ").append(i).append(") out vec4 outColor").append(i).append(";\n");
            }
        }
    }

    /**
     * Renames the sampler {@code from} to {@code to} where the program declares it ({@code uniform sampler2D tex;}): the declaration and every use in code.
     * The program is left alone when it does not declare {@code from} or already declares {@code to}.
     */
    public static String renameSampler(final String source, final String from, final String to) {
        Pattern declaration = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2D\\s+" + from + "\\s*;");
        if (!declaration.matcher(code(source)).find()) return source;
        if (Pattern.compile("\\b" + to + "\\b").matcher(code(source)).find()) return source;
        Pattern word = Pattern.compile("(?<![\\w.])" + from + "(?!\\w)");
        StringBuilder out = new StringBuilder(source.length());
        scan(source, (start, end, isCode) -> {
            String part = source.substring(start, end);
            out.append(isCode ? word.matcher(part).replaceAll(to) : part);
        });
        return out.toString();
    }

    /**
     * The source with its line continuations (a backslash at the end of a line) joined, which the compiler accepts only from newer versions of the language:
     * a macro written over several lines becomes one line, and a backslash at the end of a comment line (pictures drawn in comments have them) is dropped.
     * The lines that were joined are made up for with empty ones after, so the line numbers of the compiler's messages stay those of the file.
     */
    public static String spliceLines(final String source) {
        if (source.indexOf('\\') < 0) return source;
        StringBuilder out = new StringBuilder(source.length());
        scan(source, (start, end, isCode) -> {
            String part = source.substring(start, end);
            if (isCode) {
                int owed = 0;
                StringBuilder code = new StringBuilder();
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c == '\\' && i + 1 < part.length() && part.charAt(i + 1) == '\n') {
                        code.append(' ');
                        owed++;
                        i++;
                    } else if (c == '\n' && owed > 0) {
                        code.append("\n".repeat(owed + 1));
                        owed = 0;
                    } else {
                        code.append(c);
                    }
                }
                out.append(code);
            } else {
                out.append(part.replaceAll("\\\\(?=\\n)", " "));
            }
        });
        return out.toString();
    }

    /** The source with its comments blanked, same length. */
    static String code(final String source) {
        StringBuilder out = new StringBuilder(source.length());
        scan(source, (start, end, isCode) -> {
            String part = source.substring(start, end);
            out.append(isCode ? part : part.replaceAll("[^\\n]", " "));
        });
        return out.toString();
    }

    private interface Segment {
        void accept(int start, int end, boolean isCode);
    }

    /** Splits the text into stretches of code and of comment, in order, covering all of it. */
    private static void scan(final String s, final Segment segment) {
        int i = 0;
        int codeStart = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                if (i > codeStart) segment.accept(codeStart, i, true);
                int end = s.indexOf('\n', i);
                if (end < 0) end = n;
                segment.accept(i, end, false);
                i = codeStart = end;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                if (i > codeStart) segment.accept(codeStart, i, true);
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                segment.accept(i, end, false);
                i = codeStart = end;
            } else {
                i++;
            }
        }
        if (n > codeStart) segment.accept(codeStart, n, true);
    }
}
