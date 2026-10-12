package com.metallumextra.shader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack's vertex stage and geometry stage as one compute kernel, since Metal has no geometry stage (see {@link ComputeCompiler}).
 * <p>
 * One invocation handles one input triangle: it loads the triangle's three vertices by index, runs the vertex stage on each (the same code the world's
 * programs run, adapted to the vertex format), then runs the geometry stage once with the three results as its inputs. Each vertex the geometry stage
 * emits is written, with the geometry stage's outputs packed as floats, into the output buffer in slot {@code triangle * maxVertices + emitted}. The
 * slots nothing was emitted into are zeroed, which draws nothing.
 * <p>
 * The geometry stage's code is taken from its own file, without its {@code #version} and {@code #include} lines: the vertex stage's text already holds
 * the headers both of them include, and two copies of a header's functions would not compile in one unit.
 */
public final class GeometryKernel {
    /** The vertex attributes of one input vertex, as the buffer holds them: {@code components} floats (or bytes, normalised, for a colour) from {@code byteOffset}. */
    public record Attribute(String name, int byteOffset, int components, boolean bytes) {
    }

    /** How the input vertices are laid out in their buffer. */
    public record VertexLayout(int strideBytes, List<Attribute> attributes) {
    }

    /** An output of the geometry stage, in the order it is packed: its name in the stage, and its number of floats. gl_Position is first. */
    public record Varying(String name, int components) {
    }

    /**
     * @param glsl the compute kernel, {@code #version 430}
     * @param maxVertices what the geometry stage may emit for one triangle
     * @param outputs what each emitted vertex holds, in order
     * @param outputFloats the floats one emitted vertex takes
     */
    public record Built(String glsl, int maxVertices, List<Varying> outputs, int outputFloats) {
    }

    /** The bindings of the kernel's buffers, by the names the kernel declares them with. */
    public static final String INPUT = "MxVertices", INDICES = "MxIndices", OUTPUT = "MxOutput", COUNT = "MxCount";
    /** Threads per group; each thread takes one triangle, so a dispatch covers {@code ceil(triangles / LOCAL_SIZE)} groups. */
    public static final int LOCAL_SIZE = 64;

    /** The lines that must be the kernel's own: its version, and extensions the stages ask for (which a compute kernel has no use for). */
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*(?:version|extension)[^\\n]*\\n?");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[^\\n]*\\n?");
    private static final Pattern IN_DECL = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?(?:flat\\s+)?\\bin\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern OUT_DECL = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?(?:flat\\s+)?\\bout\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern GS_IN_ARRAY = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?(?:flat\\s+)?\\bin\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*\\[\\s*\\]\\s*;");
    private static final Pattern GS_INPUT_LAYOUT = Pattern.compile("layout\\s*\\(\\s*(triangles|lines|points|lines_adjacency|triangles_adjacency)\\s*\\)\\s*in\\s*;");
    private static final Pattern GS_OUTPUT_LAYOUT = Pattern.compile("layout\\s*\\(\\s*(triangle_strip|line_strip|points)\\s*,\\s*max_vertices\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Pattern EMIT = Pattern.compile("\\bEmitVertex\\s*\\(\\s*\\)\\s*;");
    private static final Pattern END = Pattern.compile("\\bEndPrimitive\\s*\\(\\s*\\)\\s*;");
    private static final Pattern GL_POSITION = Pattern.compile("(?<![\\w.])gl_Position\\b");
    private static final String GS_POSITION = "mx_gs_position";
    /** The names the kernel gives the builtins it needs: a name that starts with gl_ is reserved, so none of these may. */
    private static final String GL_IN_ARRAY = "mx_gl_in";
    private static final String VS_POSITION = "mx_position";
    private static final Pattern GL_IN_POSITION = Pattern.compile("\\bgl_in\\s*\\[([^\\]]+)\\]\\s*\\.\\s*gl_Position\\b");
    private static final Pattern GL_IN_ACCESS = Pattern.compile("\\bgl_in\\b");
    private static final String VS_OUT_PREFIX = "mx_vo_";

    private GeometryKernel() {
    }

    /**
     * The kernel for a vertex stage and a geometry stage.
     *
     * @param vertex the vertex stage, already adapted to its vertex format ({@link IrisWorldAdapter#adapt})
     * @param geometry the geometry stage's own file, as the pack has it
     * @param layout how the input vertices are laid out
     * @throws IllegalArgumentException if the geometry stage uses something this does not translate (a varying of a type other than float, vec2, vec3 or vec4)
     */
    public static Built build(final String vertex, final String geometry, final VertexLayout layout) {
        Matcher maxMatch = GS_OUTPUT_LAYOUT.matcher(geometry);
        if (!maxMatch.find()) throw new IllegalArgumentException("the geometry stage has no output layout (triangle_strip, max_vertices = n)");
        int maxVertices = Integer.parseInt(maxMatch.group(2));

        // ---- the vertex stage ----
        String vs = VERSION.matcher(vertex).replaceAll("");
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher in = IN_DECL.matcher(vs);
        StringBuilder vsIn = new StringBuilder();
        while (in.find()) {
            attributes.put(in.group(2), in.group(1));
            in.appendReplacement(vsIn, Matcher.quoteReplacement(in.group(1) + " " + in.group(2) + ";"));
        }
        in.appendTail(vsIn);
        vs = vsIn.toString();
        Map<String, String> vsOuts = new LinkedHashMap<>();
        Matcher out = OUT_DECL.matcher(vs);
        StringBuilder vsOut = new StringBuilder();
        while (out.find()) {
            vsOuts.put(out.group(2), out.group(1));
            out.appendReplacement(vsOut, "");
        }
        out.appendTail(vsOut);
        vs = vsOut.toString();
        // Each vertex output becomes a global with a prefix, so the geometry stage's inputs of the same names do not clash with it.
        for (String name : vsOuts.keySet()) {
            vs = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\b").matcher(vs).replaceAll(VS_OUT_PREFIX + name);
        }
        vs = GL_POSITION.matcher(vs).replaceAll(VS_POSITION);
        vs = MAIN.matcher(vs).replaceAll("void mx_vs()");

        // ---- the geometry stage ----
        String gs = INCLUDE.matcher(VERSION.matcher(geometry).replaceAll("")).replaceAll("");
        gs = GS_INPUT_LAYOUT.matcher(gs).replaceAll("");
        gs = GS_OUTPUT_LAYOUT.matcher(gs).replaceAll("");
        Map<String, String> gsIn = new LinkedHashMap<>();
        Matcher inArray = GS_IN_ARRAY.matcher(gs);
        StringBuilder gsIns = new StringBuilder();
        while (inArray.find()) {
            gsIn.put(inArray.group(2), inArray.group(1));
            inArray.appendReplacement(gsIns, Matcher.quoteReplacement(inArray.group(1) + " " + inArray.group(2) + "[3];"));
        }
        inArray.appendTail(gsIns);
        gs = gsIns.toString();
        List<Varying> outputs = new ArrayList<>();
        outputs.add(new Varying(GS_POSITION, 4));
        Matcher gsOut = OUT_DECL.matcher(gs);
        StringBuilder gsOuts = new StringBuilder();
        while (gsOut.find()) {
            String name = gsOut.group(2);
            if (name.equals("gl_Position")) throw new IllegalArgumentException("the geometry stage declares gl_Position itself");
            outputs.add(new Varying(name, components(gsOut.group(1))));
            gsOut.appendReplacement(gsOuts, Matcher.quoteReplacement(gsOut.group(1) + " " + name + ";"));
        }
        gsOut.appendTail(gsOuts);
        gs = GL_IN_POSITION.matcher(gsOuts.toString()).replaceAll(m -> Matcher.quoteReplacement(GL_IN_ARRAY + "[" + m.group(1) + "]." + VS_POSITION));
        gs = GL_POSITION.matcher(gs).replaceAll(GS_POSITION);
        gs = GL_IN_ACCESS.matcher(gs).replaceAll(GL_IN_ARRAY);
        gs = EMIT.matcher(gs).replaceAll("mx_emit();");
        gs = END.matcher(gs).replaceAll("mx_end();");
        gs = MAIN.matcher(gs).replaceAll("void mx_gs()");
        for (String name : gsIn.keySet()) {
            if (!vsOuts.containsKey(name)) throw new IllegalArgumentException("the geometry stage reads " + name + ", which the vertex stage does not write");
        }

        int floats = 0;
        for (Varying varying : outputs) floats += varying.components();

        // ---- the kernel ----
        StringBuilder k = new StringBuilder("#version 430 core\n");
        k.append("layout(local_size_x = ").append(LOCAL_SIZE).append(", local_size_y = 1, local_size_z = 1) in;\n");
        k.append("layout(std430, binding = 0) readonly buffer ").append(INPUT).append(" { uint mx_in[]; };\n");
        k.append("layout(std430, binding = 1) readonly buffer ").append(INDICES).append(" { uint mx_indices[]; };\n");
        k.append("layout(std430, binding = 2) writeonly buffer ").append(OUTPUT).append(" { float mx_out[]; };\n");
        k.append("layout(std430, binding = 3) readonly buffer ").append(COUNT).append(" { uint mx_triangles; };\n");
        k.append("#define MX_MAX_VERTICES ").append(maxVertices).append("u\n");
        k.append("#define MX_STRIDE ").append(floats).append("u\n");
        k.append("vec4 ").append(VS_POSITION).append(";\n");
        k.append("struct mx_gl_in_t { vec4 ").append(VS_POSITION).append("; };\nmx_gl_in_t ").append(GL_IN_ARRAY).append("[3];\n");
        k.append("uint mx_primitive;\nuint mx_emitted;\n");
        k.append("vec4 ").append(GS_POSITION).append(";\n");
        for (Map.Entry<String, String> entry : vsOuts.entrySet()) k.append(entry.getValue()).append(' ').append(VS_OUT_PREFIX).append(entry.getKey()).append(";\n");
        k.append("void mx_end() {\n}\n");
        k.append("void mx_emit();\n");
        k.append(vs).append('\n');
        k.append(gs).append('\n');

        // Loads one input vertex's attributes, by the vertex format.
        k.append("void mx_load(uint v) {\n    uint b = v * ").append(layout.strideBytes() / 4).append("u;\n");
        for (Attribute attribute : layout.attributes()) {
            String type = attributes.get(attribute.name());
            if (type == null) continue;
            int word = attribute.byteOffset() / 4;
            if (attribute.bytes()) {
                k.append("    ").append(attribute.name()).append(" = unpackUnorm4x8(mx_in[b + ").append(word).append("u]);\n");
                continue;
            }
            StringBuilder parts = new StringBuilder();
            for (int c = 0; c < attribute.components(); c++) {
                if (c > 0) parts.append(", ");
                parts.append(type.startsWith("i") || type.startsWith("u") ? "int(mx_in[b + " + (word + c) + "u])" : "uintBitsToFloat(mx_in[b + " + (word + c) + "u])");
            }
            k.append("    ").append(attribute.name()).append(" = ").append(type).append('(').append(parts).append(");\n");
        }
        k.append("}\n");

        // Packs what the geometry stage emitted into the slot the vertex takes.
        k.append("void mx_emit() {\n    if (mx_emitted >= MX_MAX_VERTICES) return;\n");
        k.append("    uint base = (mx_primitive * MX_MAX_VERTICES + mx_emitted) * MX_STRIDE;\n");
        int at = 0;
        for (Varying varying : outputs) {
            for (int c = 0; c < varying.components(); c++) {
                String access = varying.components() == 1 ? varying.name() : varying.name() + "." + "xyzw".charAt(c);
                k.append("    mx_out[base + ").append(at + c).append("u] = ").append(access).append(";\n");
            }
            at += varying.components();
        }
        k.append("    mx_emitted++;\n}\n");

        k.append("void main() {\n    uint t = gl_GlobalInvocationID.x;\n    if (t >= mx_triangles) return;\n");
        k.append("    mx_primitive = t;\n    mx_emitted = 0u;\n");
        k.append("    for (int i = 0; i < 3; i++) {\n        mx_load(mx_indices[t * 3u + uint(i)]);\n        mx_vs();\n");
        k.append("        ").append(GL_IN_ARRAY).append("[i].").append(VS_POSITION).append(" = ").append(VS_POSITION).append(";\n");
        for (String name : gsIn.keySet()) k.append("        ").append(name).append("[i] = ").append(VS_OUT_PREFIX).append(name).append(";\n");
        k.append("    }\n    mx_gs();\n");
        k.append("    for (uint s = mx_emitted; s < MX_MAX_VERTICES; s++) {\n");
        k.append("        for (uint j = 0u; j < MX_STRIDE; j++) mx_out[(t * MX_MAX_VERTICES + s) * MX_STRIDE + j] = 0.0;\n    }\n}\n");

        return new Built(k.toString(), maxVertices, List.copyOf(outputs), floats);
    }

    private static final Pattern INCLUDE_NAME = Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]*\"([^\"]+)\"");

    /**
     * The geometry stage's own code: its file with the files it includes read in its place, recursively. A file the vertex stage includes too (the
     * pack's shared headers) is left out, since the vertex stage's text already has it. Paths are as {@code ShaderPack#read} takes them.
     */
    public static String body(final com.metallumextra.shader.pack.ShaderPack pack, final String geometryPath, final String vertexPath) {
        java.util.Set<String> provided = new java.util.HashSet<>();
        collectIncludes(pack, vertexPath, provided);
        return expand(pack, geometryPath, provided, new java.util.HashSet<>());
    }

    private static void collectIncludes(final com.metallumextra.shader.pack.ShaderPack pack, final String path, final java.util.Set<String> into) {
        String text = pack.read(path);
        if (text == null) return;
        Matcher include = INCLUDE_NAME.matcher(text);
        while (include.find()) {
            String target = resolve(path, include.group(1));
            if (into.add(target)) collectIncludes(pack, target, into);
        }
    }

    private static String expand(final com.metallumextra.shader.pack.ShaderPack pack, final String path, final java.util.Set<String> provided,
                                 final java.util.Set<String> seen) {
        String text = pack.read(path);
        if (text == null) throw new IllegalArgumentException("the geometry stage " + path + " is not in the pack");
        seen.add(path);
        StringBuilder out = new StringBuilder(text.length());
        Matcher include = INCLUDE_NAME.matcher(text);
        int at = 0;
        while (include.find()) {
            out.append(text, at, include.start());
            String target = resolve(path, include.group(1));
            if (!provided.contains(target) && !seen.contains(target)) out.append(expand(pack, target, provided, seen)).append('\n');
            at = include.end();
        }
        out.append(text.substring(at));
        return out.toString();
    }

    /** An include's file: a name that starts with {@code /} counts from the shaders folder, any other from the folder of the file that includes it. */
    static String resolve(final String from, final String name) {
        if (name.startsWith("/")) return name.substring(1);
        int slash = from.lastIndexOf('/');
        return slash < 0 ? name : from.substring(0, slash + 1) + name;
    }

    private static int components(final String type) {
        return switch (type) {
            case "float" -> 1;
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> throw new IllegalArgumentException("the geometry stage's output type " + type + " is not translated (float, vec2, vec3 and vec4 are)");
        };
    }
}
