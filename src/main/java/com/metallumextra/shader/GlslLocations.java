package com.metallumextra.shader;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives the inputs and outputs of a program's shaders the numbers the game's shader compiler demands.
 * <p>
 * The game compiles its shaders for Vulkan: every {@code in} and {@code out} needs a {@code layout(location = n)}, and the vertex and fragment stage
 * must agree on the number of what passes between them. A shader pack's programs name their inputs and outputs and leave the numbers to the driver, so
 * this works them out: what the vertex stage writes and the fragment stage reads gets the same number in both, taken in the order the names first
 * appear. A vertex stage's own inputs are matched to the game's vertex format by name, and a fragment stage's own outputs are numbered in order, so
 * any free numbers do for those.
 */
public final class GlslLocations {
    /** {@code [layout(...)] [flat|smooth|...] in|out|varying|attribute [precision] type name[[n]] ;} starting a line. */
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^([ \\t]*)((?:layout\\s*\\([^)]*\\)\\s*)?)((?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*)(in|out|varying|attribute)\\s+"
                    + "((?:(?:highp|mediump|lowp)\\s+)?(\\w+))\\s+([^;(){}=]+);");
    /** One name in a declaration's list, with its array length if it has one. */
    private static final Pattern DECLARATOR = Pattern.compile("^\\s*(\\w+)\\s*(\\[\\s*(\\d+)\\s*\\])?\\s*$");
    private static final Pattern LOCATION = Pattern.compile("location\\s*=\\s*(\\d+)");
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#version[^\\n]*\\n");
    private static final Pattern EXTENSION_LINE = Pattern.compile("(?m)^[ \\t]*#extension[ \\t]+GL_ARB_separate_shader_objects\\b");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    /** The fragment outputs that have fewer than four components, and what is added after them to fill four. */
    private static final Map<String, String> WIDEN = Map.of("float", ", 0.0, 0.0, 1.0", "vec2", ", 0.0, 1.0", "vec3", ", 1.0");
    private static final Pattern VERTEX_ID = Pattern.compile("\\bgl_VertexID\\b");
    private static final Pattern INSTANCE_ID = Pattern.compile("\\bgl_InstanceID\\b");

    private GlslLocations() {
    }

    /** One name declared by a statement: its array length text, how many locations it takes up, and the number it already has (or -1). */
    private record Variable(String name, String array, int size, int explicit) {
    }

    /** One statement found: where it is, what it declares and whether it is an output ({@code out} false for an input). */
    private record Statement(int start, int end, String indent, String interpolation, boolean out, String type, List<Variable> variables) {
    }

    /**
     * The source of one stage with all its inputs and outputs numbered.
     *
     * @param source the stage to number
     * @param other  the other stage of the same program, which decides the numbers of what passes between them; null if there is none
     * @param vertex whether {@code source} is the vertex stage
     */
    private static final Pattern STORAGE_MACRO = Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+(\\w+)[ \\t]+(in|out|varying|attribute|flat in|flat out)[ \\t]*(?://.*)?$");

    /**
     * A pack that spells {@code in} and {@code out} with a macro of its own ({@code #define in_out out} for the vertex stage, {@code in} for the fragment stage) is read
     * with the macro replaced where it is used, since the declarations are looked for before the preprocessor runs.
     */
    private static String storageMacrosExpanded(final String source) {
        Matcher macro = STORAGE_MACRO.matcher(source);
        Map<String, String> bodies = new HashMap<>();
        while (macro.find()) bodies.put(macro.group(1), macro.group(2));
        if (bodies.isEmpty()) return source;
        StringBuilder out = new StringBuilder(source.length());
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.stripLeading().startsWith("#")) {
                for (Map.Entry<String, String> entry : bodies.entrySet()) {
                    if (line.contains(entry.getKey())) line = line.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
                }
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static final Pattern TYPE_ARRAY = Pattern.compile("\\b(in|out|varying|attribute)(\\s+(?:(?:highp|mediump|lowp)\\s+)?)(\\w+)\\s*\\[\\s*(\\d+)\\s*\\](\\s+)(\\w+)\\s*;");

    /** {@code out vec3[9] name;} (the array on the type) is {@code out vec3 name[9];}, which is the form the rest of this class reads. */
    private static String arrayOnName(final String source) {
        return TYPE_ARRAY.matcher(source).replaceAll("$1$2$3$5$6[$4];");
    }

    public static String fit(final String source0, final @Nullable String other0, final boolean vertex) {
        String source = arrayOnName(storageMacrosExpanded(source0));
        String other = other0 == null ? null : arrayOnName(storageMacrosExpanded(other0));
        Map<String, List<Member>> structTypes = structMembers(other == null ? source : source + "\n" + other);
        Map<String, Integer> structs = structSlots(structTypes);
        List<Statement> mine = scan(source, vertex, structs);
        List<Statement> theirs = other == null ? List.of() : scan(other, !vertex, structs);
        List<Statement> vertexSide = vertex ? mine : theirs;
        List<Statement> fragmentSide = vertex ? theirs : mine;

        // What passes from the vertex stage to the fragment stage, numbered the same way whichever of the two is being fitted.
        Map<String, Integer> shared = new HashMap<>();
        BitSet used = new BitSet();
        List<Variable> passing = new ArrayList<>();
        for (Statement st : vertexSide) if (st.out) passing.addAll(st.variables);
        for (Statement st : fragmentSide) if (!st.out) passing.addAll(st.variables);
        for (Variable v : passing) {
            if (v.explicit >= 0 && shared.putIfAbsent(v.name, v.explicit) == null) used.set(v.explicit, v.explicit + v.size);
        }
        for (Variable v : passing) {
            if (v.explicit < 0 && !shared.containsKey(v.name)) {
                if (shared.containsKey("v_" + v.name)) {
                    shared.put(v.name, shared.get("v_" + v.name));
                    continue;
                }
                int location = next(used, v.size);
                used.set(location, location + v.size);
                shared.put(v.name, location);
            }
        }

        // The stage's own inputs (vertex) or outputs (fragment).
        BitSet own = new BitSet();
        Map<String, Integer> ownByName = new HashMap<>();
        for (Statement st : mine) {
            if (!isOwn(st, vertex)) continue;
            for (Variable v : st.variables) {
                if (v.explicit >= 0) {
                    own.set(v.explicit, v.explicit + v.size);
                    ownByName.putIfAbsent(v.name, v.explicit);
                }
            }
        }

        StringBuilder out = new StringBuilder(source.length() + 256);
        // What main does before the pack's code (a struct arriving from the other stage is put together) and after it (one leaving is taken apart; a narrow output is widened).
        StringBuilder head = new StringBuilder(), tail = new StringBuilder();
        int at = 0;
        for (Statement st : mine) {
            out.append(source, at, st.start);
            // One statement per name, all on the line the statement was on, so the compiler's line numbers still point at the right place.
            boolean first = true;
            for (Variable v : st.variables) {
                int location = v.explicit;
                if (location < 0) {
                    if (isOwn(st, vertex)) {
                        Integer known = ownByName.get(v.name);
                        if (known == null) {
                            known = next(own, v.size);
                            own.set(known, known + v.size);
                            ownByName.put(v.name, known);
                        }
                        location = known;
                    } else {
                        location = shared.get(v.name);
                    }
                }
                String widened = !vertex && st.out && v.array.isEmpty() ? WIDEN.get(st.type) : null;
                if (structTypes.containsKey(st.type) && v.array.isEmpty()) {
                    // The game cannot pass a struct between stages: its members are passed one by one, and the struct is a plain variable that is copied from or to them.
                    List<Member> leaves = new ArrayList<>();
                    leaves(st.type, "", structTypes, leaves);
                    out.append(first ? st.indent : " ").append(st.type).append(' ').append(v.name).append(';');
                    // The declaration may be in a branch of the program that is off: what main does for it is only there when the branch is on.
                    out.append("\n#define MX_HAS_").append(v.name).append("\n");
                    head.append(st.out ? "" : "#ifdef MX_HAS_" + v.name + "\n");
                    tail.append(st.out ? "#ifdef MX_HAS_" + v.name + "\n" : "");
                    int at2 = location;
                    for (Member leaf : leaves) {
                        String flat = "mx_v_" + v.name + "_" + leaf.name.replace('.', '_');
                        out.append(" layout(location = ").append(at2).append(") ").append(st.interpolation).append(st.out ? "out " : "in ").append(leaf.type).append(' ').append(flat).append(leaf.array).append(';');
                        at2 += slots(leaf.type, leaf.arrayLength, structs);
                        String line = st.out ? "    " + flat + " = " + v.name + "." + leaf.name + ";\n" : "    " + v.name + "." + leaf.name + " = " + flat + ";\n";
                        (st.out ? tail : head).append(line);
                    }
                    (st.out ? tail : head).append("#endif\n");
                } else if (widened != null) {
                    // Metal wants a fragment's output to fill the target's four components: the pack's variable stays, and a copy of it is written out (see below).
                    out.append(first ? st.indent : " ").append("layout(location = ").append(location).append(") out vec4 mx_out_").append(v.name).append("; ")
                            .append(st.type).append(' ').append(v.name).append(" = ").append(st.type).append("(0.0);");
                    out.append("\n#define MX_HAS_OUT_").append(v.name).append("\n");
                    tail.append("#ifdef MX_HAS_OUT_").append(v.name).append("\n    mx_out_").append(v.name).append(" = vec4(").append(v.name).append(widened).append(");\n#endif\n");
                } else {
                    out.append(first ? st.indent : " ").append("layout(location = ").append(location).append(") ").append(st.interpolation).append(st.out ? "out " : "in ")
                            .append(st.type).append(' ').append(v.name).append(v.array).append(';');
                }
                first = false;
            }
            at = st.end;
        }
        out.append(source, at, source.length());
        String result = out.toString();
        if (vertex) {
            // What the fragment stage reads and the vertex stage never declares reaches it as undefined in OpenGL, which packs rely on when a branch is off in one stage
            // only; the game's compiler wants the output to be there. The declarations go on the line of main, so no line moves.
            java.util.Set<String> written = new java.util.HashSet<>();
            for (Statement st : vertexSide) if (st.out) for (Variable v : st.variables) written.add(v.name);
            StringBuilder missing = new StringBuilder();
            for (Statement st : fragmentSide) {
                if (st.out || structTypes.containsKey(st.type)) continue;
                for (Variable v : st.variables) {
                    Integer location = shared.get(v.name);
                    if (location == null || written.contains(v.name) || written.contains("v_" + v.name) || !written.add(v.name)) continue;
                    missing.append("layout(location = ").append(location).append(") ").append(st.interpolation).append("out ").append(st.type).append(' ').append(v.name).append(v.array).append("; ");
                }
            }
            if (missing.length() > 0) {
                // On the first empty line after the header, so that the functions the program defines before main can read them; else on main's line.
                int insert = -1;
                int from = com.metallumextra.shader.pack.LegacyGlsl.headerEnd(result);
                int lineStart = result.indexOf('\n', from) + 1;
                while (lineStart > 0 && lineStart < result.length()) {
                    int lineEnd = result.indexOf('\n', lineStart);
                    if (lineEnd < 0) break;
                    if (result.substring(lineStart, lineEnd).isBlank()) {
                        insert = lineStart;
                        break;
                    }
                    lineStart = lineEnd + 1;
                }
                Matcher at3 = MAIN.matcher(result);
                if (insert < 0 && at3.find()) insert = at3.start();
                if (insert >= 0) result = result.substring(0, insert) + missing + result.substring(insert);
            }
        }
        if (head.length() + tail.length() > 0) {
            Matcher main = MAIN.matcher(result);
            if (main.find()) result = main.replaceAll("void mx_main_()") + "\nvoid main() {\n" + head + "    mx_main_();\n" + tail + "}\n";
        }
        return vulkanize(result);
    }

    /** Whether a statement declares the stage's own things: a vertex stage's inputs or a fragment stage's outputs. */
    private static boolean isOwn(final Statement st, final boolean vertex) {
        return vertex ? !st.out : st.out;
    }

    /** {@code gl_VertexID} is {@code gl_VertexIndex} in Vulkan's GLSL, and the extension that allows locations on a plain {@code #version 330} is asked for. */
    public static String vulkanize(final String source) {
        String text = VERTEX_ID.matcher(source).replaceAll("gl_VertexIndex");
        text = INSTANCE_ID.matcher(text).replaceAll("gl_InstanceIndex");
        Matcher version = VERSION.matcher(text);
        if (!version.find()) return text;
        StringBuilder extensions = new StringBuilder();
        if (!EXTENSION_LINE.matcher(text).find()) extensions.append("#extension GL_ARB_separate_shader_objects : require\n");
        // fma is part of the language in GLSL 400 and up, which the packs' programs (and the game's own) are not written in.
        if (text.contains("fma(") && !text.contains("GL_ARB_gpu_shader5")) extensions.append("#extension GL_ARB_gpu_shader5 : enable\n");
        return extensions.length() == 0 ? text : text.substring(0, version.end()) + extensions + text.substring(version.end());
    }

    private static int next(final BitSet used, final int size) {
        int location = 0;
        while (true) {
            int clash = used.nextSetBit(location);
            if (clash < 0 || clash >= location + size) return location;
            location = clash + 1;
        }
    }

    private static List<Statement> scan(final String source, final boolean vertex, final Map<String, Integer> structs) {
        List<Statement> found = new ArrayList<>();
        Matcher m = DECLARATION.matcher(source);
        while (m.find()) {
            String keyword = m.group(4);
            // Legacy keywords: a vertex stage's varying is an output, a fragment stage's an input; an attribute is an input.
            boolean out = keyword.equals("out") || keyword.equals("varying") && vertex;
            String layout = m.group(2);
            Matcher explicit = LOCATION.matcher(layout);
            int location = explicit.find() ? Integer.parseInt(explicit.group(1)) : -1;
            // Another kind of layout qualifier (an index, a component): left to the compiler.
            if (location < 0 && !layout.isBlank()) continue;
            List<Variable> variables = new ArrayList<>();
            boolean valid = true;
            for (String declarator : m.group(7).split(",")) {
                Matcher d = DECLARATOR.matcher(declarator);
                if (!d.matches()) {
                    valid = false;
                    break;
                }
                variables.add(new Variable(d.group(1), d.group(2) == null ? "" : d.group(2), slots(m.group(6), d.group(3), structs), location));
            }
            // A location on a statement that declares several names says nothing the compiler cannot work out: left alone.
            if (!valid || (location >= 0 && variables.size() > 1)) continue;
            found.add(new Statement(m.start(), m.end(), m.group(1), m.group(3), out, m.group(5), variables));
        }
        return found;
    }

    private static final Pattern STRUCT = Pattern.compile("\\bstruct\\s+(\\w+)\\s*\\{([^}]*)\\}\\s*;");
    private static final Pattern MEMBER = Pattern.compile("(?:(?:highp|mediump|lowp|flat|smooth|noperspective)\\s+)*(\\w+)\\s+([^;]+);");

    /** One member of a struct: its type, name and array suffix ({@code [3]} or empty). */
    private record Member(String type, String name, String array, @Nullable String arrayLength) {
    }

    /** The structs of the source, with their members. */
    private static Map<String, List<Member>> structMembers(final String source) {
        Map<String, List<Member>> result = new HashMap<>();
        Matcher m = STRUCT.matcher(stripComments(source));
        while (m.find()) {
            List<Member> members = new ArrayList<>();
            Matcher member = MEMBER.matcher(m.group(2));
            while (member.find()) {
                for (String declarator : member.group(2).split(",")) {
                    Matcher d = DECLARATOR.matcher(declarator);
                    if (d.matches()) members.add(new Member(member.group(1), d.group(1), d.group(2) == null ? "" : d.group(2), d.group(3)));
                }
            }
            result.put(m.group(1), members);
        }
        return result;
    }

    /** How many locations each struct of the source takes up when it is passed between stages: the sum of its members, each counted as a variable is. */
    private static Map<String, Integer> structSlots(final Map<String, List<Member>> members) {
        Map<String, Integer> result = new HashMap<>();
        for (String name : members.keySet()) slotsOfStruct(name, members, result);
        return result;
    }

    private static int slotsOfStruct(final String name, final Map<String, List<Member>> members, final Map<String, Integer> known) {
        Integer have = known.get(name);
        if (have != null) return have;
        known.put(name, 1);
        int total = 0;
        for (Member m : members.getOrDefault(name, List.of())) {
            int each = members.containsKey(m.type) ? slotsOfStruct(m.type, members, known) : slots(m.type, null, known);
            total += each * (m.arrayLength == null ? 1 : Math.max(1, Integer.parseInt(m.arrayLength)));
        }
        total = Math.max(1, total);
        known.put(name, total);
        return total;
    }

    /** The plain members a struct is made of, nested structs opened up: each with the dotted path to it. */
    private static void leaves(final String struct, final String path, final Map<String, List<Member>> members, final List<Member> out) {
        for (Member m : members.getOrDefault(struct, List.of())) {
            String at = path.isEmpty() ? m.name : path + "." + m.name;
            if (members.containsKey(m.type) && m.arrayLength == null) leaves(m.type, at, members, out);
            else out.add(new Member(m.type, at, m.array, m.arrayLength));
        }
    }

    private static String stripComments(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /** How many locations a variable takes up: a matrix takes one per column, a struct one per member, an array one per element. */
    private static int slots(final String type, final @Nullable String arrayLength, final Map<String, Integer> structs) {
        int columns = switch (type) {
            case "mat2", "mat2x2", "mat2x3", "mat2x4", "dmat2" -> 2;
            case "mat3", "mat3x2", "mat3x3", "mat3x4", "dmat3" -> 3;
            case "mat4", "mat4x2", "mat4x3", "mat4x4", "dmat4" -> 4;
            default -> structs.getOrDefault(type, 1);
        };
        return columns * (arrayLength == null ? 1 : Math.max(1, Integer.parseInt(arrayLength)));
    }
}
