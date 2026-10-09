package com.metallumextra.shader;

import com.metallumextra.shader.pack.BuiltinPack;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.pack.PackException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a program of a standard pack that draws terrain fit Sodium's pipeline.
 * <p>
 * The pipeline is Sodium's: its vertex format, its samplers ({@code u_BlockTex}, {@code u_LightTex}) and its push constants. The
 * program is written for Iris: it reads {@code vaPosition}, {@code gtexture} and the like. So the program's own declarations of
 * those names are taken out and replaced by a prelude (the resource files {@code internal/terrain_vertex.glsl} and
 * {@code internal/terrain_fragment.glsl}) that defines each name as an expression over Sodium's inputs.
 * The program's code does not change.
 */
public final class IrisTerrain {
    /** The vertex inputs the prelude provides, by name. */
    private static final String[] INPUTS = {"vaPosition", "vaColor", "vaUV0", "vaUV2", "vaNormal", "mc_Entity"};
    private static final Pattern HEADER_LINE = Pattern.compile("(?m)^\\s*(#version[^\\n]*|#extension[^\\n]*)$");
    private static final String VERTEX_PRELUDE = new BuiltinPack().read("internal/terrain_vertex.glsl");
    private static final String FRAGMENT_PRELUDE = new BuiltinPack().read("internal/terrain_fragment.glsl");

    private IrisTerrain() {
    }

    /**
     * @param vertex whether the source is the vertex stage
     * @throws PackException if the program declares something the prelude does not provide
     */
    public static String adapt(final String source, final boolean vertex) throws PackException {
        String text = source;
        if (vertex) {
            for (String name : INPUTS) {
                text = Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
            }
        }
        // The terrain's own uniforms and the two samplers: declarations out, the prelude's definitions in.
        for (String name : IrisUniforms.TERRAIN_MACROS) {
            text = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
        }
        boolean atlas = uses(text, "gtexture");
        boolean light = uses(text, "lightmap");
        text = Pattern.compile("(?m)^[ \\t]*uniform\\s+sampler2D\\s+(gtexture|lightmap)\\s*;[ \\t]*$").matcher(text).replaceAll("");

        StringBuilder prelude = new StringBuilder("\n");
        if (atlas) prelude.append("uniform sampler2D u_BlockTex;\n#define gtexture u_BlockTex\n#define atlasSize textureSize(u_BlockTex, 0)\n");
        if (light) prelude.append("uniform sampler2D u_LightTex;\n#define lightmap u_LightTex\n");
        prelude.append(vertex ? VERTEX_PRELUDE : FRAGMENT_PRELUDE).append('\n');

        int at = 0;
        Matcher header = HEADER_LINE.matcher(text);
        while (header.find()) at = header.end();
        return text.substring(0, at) + prelude + text.substring(at);
    }

    private static boolean uses(final String text, final String name) {
        return Pattern.compile("\\b" + name + "\\b").matcher(text).find();
    }
}
