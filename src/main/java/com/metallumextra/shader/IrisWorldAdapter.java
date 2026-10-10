package com.metallumextra.shader;

import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a program of a standard pack that draws something other than terrain (entities, items, particles ...) fit one of the game's pipelines.
 * <p>
 * The game's pipelines each have their own vertex format and read their matrices from the game's own uniform blocks. A program is
 * written for Iris: it reads {@code vaPosition} and {@code modelViewMatrix}. So the program's declarations of those names are taken out
 * and replaced by a prelude that defines each as an expression over what the pipeline has, with a plain default for what it lacks
 * (no normal: up; no overlay: none; no light: full). The program's code does not change.
 */
public final class IrisWorldAdapter {
    /** What a pipeline has to read from: its vertex elements, samplers and the shader it was made for. */
    public record Format(boolean color, boolean uv0, boolean uv1, boolean uv2, boolean normal, boolean modelOffset, boolean atlas, boolean light, boolean cloud, boolean overlay) {
        /** No vertex elements and no samplers: what the sky is drawn with. */
        public static final Format NONE = new Format(false, false, false, false, false, false, false, false, false, false);

        public static Format of(final RenderPipeline pipeline) {
            boolean color = false, uv0 = false, uv1 = false, uv2 = false, normal = false;
            for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
                if (binding == null) continue;
                color |= binding.contains("Color");
                uv0 |= binding.contains("UV0");
                uv1 |= binding.contains("UV1");
                uv2 |= binding.contains("UV2");
                normal |= binding.contains("Normal");
            }
            var samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
            return new Format(color, uv0, uv1, uv2, normal, pipeline.getVertexShader().getPath().equals("core/block"), samplers.contains("Sampler0"), samplers.contains("Sampler2"),
                    pipeline.getVertexShader().getPath().equals("core/rendertype_clouds"), samplers.contains("Sampler1"));
        }
    }

    /** The matrices of the programs that draw the world, which for these pipelines are the game's per-draw ones, not a frame's. */
    private static final String[] MATRICES = {"modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "normalMatrix"};
    private static final String[] INPUTS = {"vaPosition", "vaColor", "vaUV0", "vaUV1", "vaUV2", "vaNormal", "mc_Entity", "mc_chunkFade", "mc_midTexCoord", "at_tangent", "at_midBlock"};
    private static final Pattern HEADER_LINE = Pattern.compile("(?m)^\\s*(#version[^\\n]*|#extension[^\\n]*)$");
    private static final Pattern OUTPUT_LOCATION = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)(\\s*(?:flat\\s+)?out\\b)");
    private static final Pattern OUTPUT_NAMED = Pattern.compile("(?m)^([ \\t]*)out(\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?vec4\\s+outColor(\\d+)\\s*;)");

    /** What a shadow program is given for the names it reads the light's matrices by; the pack's own declarations of them are replaced. */
    static final java.util.List<String> SHADOW_MATRICES = java.util.List.of("modelViewMatrix", "projectionMatrix", "modelViewMatrixInverse", "projectionMatrixInverse",
            "normalMatrix", "shadowModelView", "shadowProjection");
    /** In the shadow map, the terrain's matrices are the light's: its position is already relative to the camera, so no model matrix is involved. */
    static final String SHADOW_PRELUDE_TERRAIN = "uniform mat4 shadowModelView;\nuniform mat4 shadowProjection;\n"
            + "#define modelViewMatrix shadowModelView\n#define projectionMatrix shadowProjection\n"
            + "#define modelViewMatrixInverse inverse(shadowModelView)\n#define projectionMatrixInverse inverse(shadowProjection)\n"
            + "#define normalMatrix transpose(inverse(mat3(shadowModelView)))\n";
    /** A model drawn into the shadow map: the game's model-view matrix is the camera's rotation and the model's pose, so the camera's part is undone and the light's put in. */
    private static final String SHADOW_PRELUDE_WORLD = "uniform mat4 shadowModelView;\nuniform mat4 shadowProjection;\nuniform mat4 gbufferModelViewInverse;\n"
            + "#define modelViewMatrix (shadowModelView * gbufferModelViewInverse * ModelViewMat)\n#define projectionMatrix shadowProjection\n"
            + "#define modelViewMatrixInverse inverse(modelViewMatrix)\n#define projectionMatrixInverse inverse(shadowProjection)\n"
            + "#define normalMatrix transpose(inverse(mat3(modelViewMatrix)))\n";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");

    /** The samplers of the buffers and shadow maps, by the names the pack reads them with and the internal names they are bound by (see {@link ShaderBindings}). */
    private static final java.util.Map<String, String> BOUND_SAMPLERS = java.util.Map.ofEntries(
            java.util.Map.entry("shadowtex0", ShaderBindings.SHADOW_MAP), java.util.Map.entry("shadowtex1", ShaderBindings.SHADOW_MAP),
            java.util.Map.entry("shadowcolor0", ShaderBindings.SHADOW_COLOR),
            java.util.Map.entry("colortex0", ShaderBindings.COLOR0), java.util.Map.entry("gcolor", ShaderBindings.COLOR0),
            java.util.Map.entry("depthtex0", ShaderBindings.DEPTH0), java.util.Map.entry("gdepthtex", ShaderBindings.DEPTH0),
            java.util.Map.entry("depthtex1", ShaderBindings.DEPTH1));

    /**
     * The game draws its clouds from a table of faces and no vertex buffer; this gives a program the vertex it would have read: the corner
     * in the camera's world, the face's shade times the cloud color, and the direction the face looks.
     */
    private static final String CLOUD_PRELUDE = """
            layout(std140) uniform CloudInfo {
                vec4 CloudColor;
                vec3 CloudOffset;
                vec3 CellSize;
            };
            uniform isamplerBuffer CloudFaces;
            const vec3 MX_CLOUD_CORNERS[24] = vec3[](
                vec3(1, 0, 0), vec3(1, 0, 1), vec3(0, 0, 1), vec3(0, 0, 0),
                vec3(0, 1, 0), vec3(0, 1, 1), vec3(1, 1, 1), vec3(1, 1, 0),
                vec3(0, 0, 0), vec3(0, 1, 0), vec3(1, 1, 0), vec3(1, 0, 0),
                vec3(1, 0, 1), vec3(1, 1, 1), vec3(0, 1, 1), vec3(0, 0, 1),
                vec3(0, 0, 1), vec3(0, 1, 1), vec3(0, 1, 0), vec3(0, 0, 0),
                vec3(1, 0, 0), vec3(1, 1, 0), vec3(1, 1, 1), vec3(1, 0, 1));
            const float MX_CLOUD_SHADE[6] = float[](0.7, 1.0, 0.8, 0.8, 0.9, 0.9);
            const vec3 MX_CLOUD_NORMALS[6] = vec3[](vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0));
            int mx_cloud_flags() {
                return texelFetch(CloudFaces, (gl_VertexIndex / 4) * 3 + 2).r;
            }
            vec3 mx_cloud_position() {
                int index = (gl_VertexIndex / 4) * 3;
                int flags = mx_cloud_flags();
                int cellX = (texelFetch(CloudFaces, index).r << 1) | ((flags & 128) >> 7);
                int cellZ = (texelFetch(CloudFaces, index + 1).r << 1) | ((flags & 64) >> 6);
                int quadVertex = gl_VertexIndex % 4;
                vec3 corner = MX_CLOUD_CORNERS[(flags & 7) * 4 + ((flags & 16) != 0 ? 3 - quadVertex : quadVertex)];
                return corner * CellSize + vec3(cellX, 0, cellZ) * CellSize + CloudOffset;
            }
            vec4 mx_cloud_color() {
                int flags = mx_cloud_flags();
                return vec4(vec3((flags & 32) != 0 ? MX_CLOUD_SHADE[1] : MX_CLOUD_SHADE[flags & 7]), 1.0) * CloudColor;
            }
            #define vaPosition mx_cloud_position()
            #define vaColor mx_cloud_color()
            #define vaNormal MX_CLOUD_NORMALS[mx_cloud_flags() & 7]
            #define vaUV0 vec2(0.0)
            #define vaUV1 ivec2(0, 10)
            #define vaUV2 ivec2(240, 240)
            #define mc_Entity vec2(-1.0, 0.0)
            #define mc_chunkFade 1.0
            #define mc_midTexCoord vec2(0.0)
            """;

    /** The sky is drawn over the whole screen from three vertices made from the vertex number: the corners of the far plane, as the camera's world sees them. */
    private static final String SKY_PRELUDE = """
            uniform mat4 gbufferModelView;
            uniform mat4 gbufferModelViewInverse;
            uniform mat4 gbufferProjection;
            uniform mat4 gbufferProjectionInverse;
            vec3 mx_sky_vertex() {
                vec2 ndc = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
                vec4 far = gbufferProjectionInverse * vec4(ndc, 1.0, 1.0);
                return mat3(gbufferModelViewInverse) * (far.xyz / far.w);
            }
            #define vaPosition mx_sky_vertex()
            #define vaColor vec4(1.0)
            #define vaUV0 vec2(0.0)
            #define vaUV1 ivec2(0, 10)
            #define vaUV2 ivec2(240, 240)
            #define vaNormal vec3(0.0, 1.0, 0.0)
            #define mc_Entity vec2(-1.0, 0.0)
            #define mc_chunkFade 1.0
            #define mc_midTexCoord vec2(0.0)
            #define modelViewMatrix gbufferModelView
            #define projectionMatrix gbufferProjection
            #define modelViewMatrixInverse gbufferModelViewInverse
            #define projectionMatrixInverse gbufferProjectionInverse
            #define normalMatrix mat3(gbufferModelView)
            #define chunkOffset vec3(0.0)
            #define textureMatrix mat4(1.0)
            #define alphaTestRef 0.0
            """;

    private IrisWorldAdapter() {
    }

    /**
     * A program for the sky (see {@link #SKY_PRELUDE}). Nothing is drawn with depth there, so the depth the vertex stage leaves is simply made valid.
     */
    public static String adaptSky(final String source, final boolean vertex) {
        String text = source;
        if (vertex) {
            for (String name : INPUTS) {
                text = Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
            }
        }
        java.util.List<String> names = new java.util.ArrayList<>(IrisUniforms.TERRAIN_MACROS);
        names.addAll(java.util.List.of(MATRICES));
        names.addAll(java.util.List.of("gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse"));
        text = stripDeclarations(text, names);
        String[] bound = boundSamplers(text);
        text = bound[0];
        StringBuilder p = new StringBuilder("\n");
        if (vertex) p.append(SKY_PRELUDE);
        else p.append("uniform mat4 gbufferModelView;\nuniform mat4 gbufferModelViewInverse;\nuniform mat4 gbufferProjection;\nuniform mat4 gbufferProjectionInverse;\n"
                + "#define modelViewMatrix gbufferModelView\n#define projectionMatrix gbufferProjection\n#define modelViewMatrixInverse gbufferModelViewInverse\n"
                + "#define projectionMatrixInverse gbufferProjectionInverse\n#define normalMatrix mat3(gbufferModelView)\n#define chunkOffset vec3(0.0)\n#define textureMatrix mat4(1.0)\n#define alphaTestRef 0.0\n");
        p.append(bound[1]);
        int at = 0;
        Matcher header = HEADER_LINE.matcher(text);
        while (header.find()) at = header.end();
        String result = text.substring(0, at) + p + text.substring(at);
        if (!vertex) return result;
        Matcher main = MAIN.matcher(result);
        if (!main.find()) return result;
        return main.replaceFirst("void mx_pack_main()") + "\nvoid main() {\n    mx_pack_main();\n    gl_Position.z = gl_Position.w * 0.5;\n}\n";
    }

    /** The source without its loose declarations of these uniforms. */
    static String stripDeclarations(final String source, final java.util.List<String> names) {
        String text = source;
        for (String name : names) {
            text = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
        }
        return text;
    }

    /**
     * The source with its declarations of the shadow map's and the buffers' samplers taken out, and what replaces them: each is read by an
     * internal name that the render pass binds for any pipeline that uses it, and the pack's name is defined as that.
     *
     * @return the source, and the lines to put after the header
     */
    static String[] boundSamplers(final String source) {
        String text = source;
        java.util.LinkedHashMap<String, java.util.List<String>> internal = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : BOUND_SAMPLERS.entrySet()) {
            Pattern declaration = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2D\\s+" + entry.getKey() + "\\s*;[ \\t]*$");
            if (!declaration.matcher(text).find()) continue;
            text = declaration.matcher(text).replaceAll("");
            internal.computeIfAbsent(entry.getValue(), k -> new java.util.ArrayList<>()).add(entry.getKey());
        }
        // Anything else the programs that draw the world read goes through a slot the plan gave it (see IrisPlan#worldSlots).
        IrisPlan plan = IrisPipeline.currentPlan();
        if (plan != null) {
            Matcher any = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2D\\s+(\\w+)\\s*;[ \\t]*$").matcher(text);
            java.util.List<String> declared = new java.util.ArrayList<>();
            while (any.find()) declared.add(any.group(1));
            for (String name : declared) {
                int slot = plan.worldSlotOf(name);
                if (slot < 0) continue;
                text = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?sampler2D\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
                internal.computeIfAbsent(ShaderBindings.WORLD_SLOT + slot, k -> new java.util.ArrayList<>()).add(name);
            }
        }
        StringBuilder lines = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : internal.entrySet()) {
            lines.append("uniform sampler2D ").append(entry.getKey()).append(";\n");
            for (String name : entry.getValue()) lines.append("#define ").append(name).append(' ').append(entry.getKey()).append('\n');
        }
        return new String[] {text, lines.toString()};
    }

    /**
     * A vertex stage's {@code main} runs, and the depth it leaves is turned from OpenGL's range, which a program for the shadow map works in,
     * into Metal's. (The game's own programs use the other way round, so only the shadow map is in OpenGL depth.)
     */
    static String shadowClip(final String vertex) {
        Matcher match = MAIN.matcher(vertex);
        if (!match.find()) return vertex;
        return match.replaceFirst("void mx_pack_main()") + "\nvoid main() {\n    mx_pack_main();\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
    }

    public static String adapt(final String source, final boolean vertex, final Format format) {
        return adapt(source, vertex, format, false);
    }

    /** @param shadow the program draws the shadow map (see {@link #SHADOW_PRELUDE_WORLD}) */
    public static String adapt(final String source, final boolean vertex, final Format format, final boolean shadow) {
        String text = source;
        if (shadow) text = stripDeclarations(text, java.util.List.of("shadowModelView", "shadowProjection", "gbufferModelViewInverse"));
        if (vertex) {
            for (String name : INPUTS) {
                text = Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
            }
        }
        java.util.List<String> names = new java.util.ArrayList<>(IrisUniforms.TERRAIN_MACROS);
        names.addAll(java.util.List.of(MATRICES));
        for (String name : names) {
            text = Pattern.compile("(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+" + name + "\\s*;[ \\t]*$").matcher(text).replaceAll("");
        }
        boolean atlas = uses(text, "gtexture") && format.atlas();
        // A sampler is only bound if the pipeline has it (a program may declare one it never reads).
        boolean light = uses(text, "lightmap") && format.light();
        text = Pattern.compile("(?m)^[ \\t]*uniform\\s+sampler2D\\s+(gtexture|lightmap)\\s*;[ \\t]*$").matcher(text).replaceAll("");
        String[] bound = boundSamplers(text);
        text = bound[0];

        StringBuilder p = new StringBuilder("\n");
        p.append("layout(std140) uniform DynamicTransforms {\n    mat4 ModelViewMat;\n    vec4 ColorModulator;\n    vec3 ModelOffset;\n    mat4 TextureMat;\n};\n");
        p.append("layout(std140) uniform Projection {\n    mat4 ProjMat;\n};\n");
        if (vertex && format.cloud()) {
            p.append(CLOUD_PRELUDE);
        } else if (vertex) {
            p.append("in vec3 Position;\n");
            if (format.color()) p.append("in vec4 Color;\n");
            if (format.uv0()) p.append("in vec2 UV0;\n");
            if (format.uv1()) p.append("in ivec2 UV1;\n");
            if (format.uv2()) p.append("in ivec2 UV2;\n");
            if (format.normal()) p.append("in vec3 Normal;\n");
            p.append("#define vaPosition ").append(format.modelOffset() ? "(Position + ModelOffset)" : "Position").append('\n');
            p.append("#define vaColor ").append(format.color() ? "(Color * ColorModulator)" : "ColorModulator").append('\n');
            p.append("#define vaUV0 ").append(format.uv0() ? "UV0" : "vec2(0.0)").append('\n');
            p.append("#define vaUV1 ").append(format.uv1() ? "UV1" : "ivec2(0, 10)").append('\n');
            p.append("#define vaUV2 ").append(format.uv2() ? "UV2" : "ivec2(240, 240)").append('\n');
            p.append("#define mc_Entity vec2(-1.0, 0.0)\n#define mc_chunkFade 1.0\n#define mc_midTexCoord vaUV0\n");
            // The game's models have no tangents: the tangent is any direction across the surface (a program that bends light by a normal map gets that map
            // wrongly turned), and there is no block the model sits in.
            p.append("#define at_tangent vec4(normalize(cross(").append(format.normal() ? "Normal" : "vec3(0.0, 1.0, 0.0)").append(", abs(").append(format.normal() ? "Normal.y" : "1.0").append(") > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0))), 1.0)\n#define at_midBlock vec4(0.0)\n");
            p.append("#define vaNormal ").append(format.normal() ? "Normal" : "vec3(0.0, 1.0, 0.0)").append('\n');
            // The overlay (the red of a hurt mob, the white of a charged creeper) lives in a texture of its own: this reads it for a vertex's overlay coordinate.
            if (format.overlay()) p.append("uniform sampler2D Sampler1;\nvec4 mx_overlay_color(ivec2 uv) {\n    return texelFetch(Sampler1, uv, 0);\n}\n");
            else p.append("vec4 mx_overlay_color(ivec2 uv) {\n    return vec4(1.0);\n}\n");
        }
        if (shadow) {
            p.append(SHADOW_PRELUDE_WORLD);
        } else {
            p.append("#define modelViewMatrix ModelViewMat\n#define projectionMatrix ProjMat\n#define modelViewMatrixInverse inverse(ModelViewMat)\n");
            p.append("#define projectionMatrixInverse inverse(ProjMat)\n#define normalMatrix transpose(inverse(mat3(ModelViewMat)))\n");
        }
        p.append(bound[1]);
        p.append("#define chunkOffset vec3(0.0)\n#define textureMatrix TextureMat\n");
        p.append("#ifdef ALPHA_CUTOUT\n#define alphaTestRef ALPHA_CUTOUT\n#else\n#define alphaTestRef 0.0\n#endif\n");
        if (atlas) p.append("uniform sampler2D Sampler0;\n#define gtexture Sampler0\n#define atlasSize textureSize(Sampler0, 0)\n");
        if (light) p.append("uniform sampler2D Sampler2;\n#define lightmap Sampler2\n");

        int at = 0;
        Matcher header = HEADER_LINE.matcher(text);
        while (header.find()) at = header.end();
        String result = text.substring(0, at) + p + text.substring(at);
        return shadow && vertex ? shadowClip(result) : result;
    }

    /**
     * A program's output {@code n} goes to buffer {@code writes[n]}, and the passes that draw the world have buffer {@code b} in attachment
     * {@code b}; so each output is moved to the attachment of its buffer.
     */
    public static String remapOutputs(final String fragment, final int[] writes) {
        Matcher match = OUTPUT_LOCATION.matcher(fragment);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            int n = Integer.parseInt(match.group(1));
            int to = n < writes.length ? writes[n] : n;
            match.appendReplacement(out, Matcher.quoteReplacement("layout(location = " + to + ")" + match.group(2)));
        }
        match.appendTail(out);
        match = OUTPUT_NAMED.matcher(out.toString());
        StringBuilder named = new StringBuilder();
        while (match.find()) {
            int n = Integer.parseInt(match.group(3));
            int to = n < writes.length ? writes[n] : n;
            match.appendReplacement(named, Matcher.quoteReplacement(match.group(1) + "layout(location = " + to + ") out" + match.group(2)));
        }
        match.appendTail(named);
        return named.toString();
    }

    private static boolean uses(final String text, final String name) {
        return Pattern.compile("\\b" + name + "\\b").matcher(text).find();
    }
}
