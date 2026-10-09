package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The standard Iris uniforms a program may declare ({@code uniform mat4 gbufferModelView;}), and how they reach it.
 * <p>
 * Metal has no loose uniforms: every value lives in a buffer. So each program's declarations of standard uniforms are
 * taken out of its source and replaced by one {@code std140} uniform block, {@value #BLOCK}, holding exactly the
 * uniforms that program declared, in the order of {@link #ALL}. The program's code does not change, since members of a block
 * without an instance name are used by their own names. {@link #layout} says where each member sits, so the values can
 * be written the way the block expects; both stages of one program get the same block.
 * <p>
 * Names Iris defines that this version does not provide are refused with the name, not left to fail as an unknown
 * identifier.
 */
public final class IrisUniforms {
    public static final String BLOCK = "IrisUniforms";

    /** A uniform's name and GLSL type; {@code gbuffers} ones exist only for the programs that draw the world. */
    public record Uniform(String name, String type, boolean gbuffers) {
        Uniform(final String name, final String type) {
            this(name, type, false);
        }
    }

    /** Every standard uniform provided, in the order they are laid out in a block. */
    public static final List<Uniform> ALL = List.of(
            new Uniform("gbufferModelView", "mat4"), new Uniform("gbufferModelViewInverse", "mat4"),
            new Uniform("gbufferProjection", "mat4"), new Uniform("gbufferProjectionInverse", "mat4"),
            new Uniform("gbufferPreviousModelView", "mat4"), new Uniform("gbufferPreviousProjection", "mat4"),
            new Uniform("cameraPosition", "vec3"), new Uniform("previousCameraPosition", "vec3"),
            new Uniform("sunPosition", "vec3"), new Uniform("moonPosition", "vec3"), new Uniform("shadowLightPosition", "vec3"),
            new Uniform("upPosition", "vec3"), new Uniform("fogColor", "vec3"), new Uniform("skyColor", "vec3"),
            new Uniform("eyeBrightness", "ivec2"), new Uniform("eyeBrightnessSmooth", "ivec2"),
            new Uniform("eyeAltitude", "float"), new Uniform("sunAngle", "float"), new Uniform("shadowAngle", "float"),
            new Uniform("rainStrength", "float"), new Uniform("wetness", "float"), new Uniform("thunderStrength", "float"),
            new Uniform("frameTime", "float"), new Uniform("frameTimeCounter", "float"),
            new Uniform("viewWidth", "float"), new Uniform("viewHeight", "float"), new Uniform("aspectRatio", "float"),
            new Uniform("screenBrightness", "float"), new Uniform("near", "float"), new Uniform("far", "float"),
            new Uniform("nightVision", "float"), new Uniform("blindness", "float"), new Uniform("darknessFactor", "float"),
            new Uniform("frameCounter", "int"), new Uniform("worldTime", "int"), new Uniform("worldDay", "int"),
            new Uniform("moonPhase", "int"), new Uniform("isEyeInWater", "int"),
            new Uniform("modelViewMatrix", "mat4", true), new Uniform("modelViewMatrixInverse", "mat4", true),
            new Uniform("projectionMatrix", "mat4", true), new Uniform("projectionMatrixInverse", "mat4", true),
            new Uniform("normalMatrix", "mat3", true));

    private static final Map<String, Uniform> BY_NAME = new LinkedHashMap<>();

    /** Iris uniforms this version does not provide yet, and why; asking for one is an error that says so. */
    private static final Map<String, String> LATER = new LinkedHashMap<>();

    static {
        for (Uniform uniform : ALL) BY_NAME.put(uniform.name, uniform);
        for (String name : List.of("shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse")) {
            LATER.put(name, "the shadow stage, which is not run yet");
        }
        for (String name : List.of("entityId", "blockEntityId", "entityColor", "blendFunc", "renderStage", "currentRenderedItemId")) {
            LATER.put(name, "the gbuffers programs, which are not run yet");
        }
        for (String name : List.of("centerDepthSmooth", "cameraPositionFract", "cameraPositionInt", "previousCameraPositionFract", "previousCameraPositionInt", "eyePosition",
                "relativeEyePosition", "playerBodyVector", "playerLookVector", "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2", "isSpectator", "hideGUI",
                "lightningBoltPosition", "fogStart", "fogEnd", "fogDensity", "fogShape", "fogMode", "biome", "biome_category", "biome_precipitation", "rainfall", "temperature",
                "ambientLight", "bedrockLevel", "cloudHeight", "hasCeiling", "hasSkylight", "heightLimit", "logicalHeightLimit", "playerMood", "constantMood", "firstPersonCamera",
                "currentDate", "currentTime", "currentYearTime", "textureFilteringMode", "endFlashPosition", "endFlashIntensity", "previousEndFlashIntensity", "darknessLightFactor")) {
            LATER.put(name, "this version");
        }
    }

    /** Names of the gbuffers programs' own uniforms that are not in the block: they are written into the source as expressions (see {@code IrisTerrain}). */
    public static final Set<String> TERRAIN_MACROS = Set.of("chunkOffset", "textureMatrix", "alphaTestRef", "atlasSize");

    private static final Pattern LOOSE = Pattern.compile("(?m)^([ \\t]*)uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+([\\w\\s,\\[\\]]+?)\\s*;[ \\t]*(//[^\\n]*)?$");
    private static final Pattern HEADER_LINE = Pattern.compile("(?m)^\\s*(#version[^\\n]*|#extension[^\\n]*)$");

    private IrisUniforms() {
    }

    /** The standard uniforms a source declares, in layout order. Samplers are not uniforms in this sense. */
    public static List<String> declared(final String source) throws PackException {
        Set<String> found = new LinkedHashSet<>();
        Matcher match = LOOSE.matcher(source);
        while (match.find()) {
            String type = match.group(2);
            if (type.startsWith("sampler") || type.startsWith("image")) continue;
            for (String name : names(match.group(3))) {
                lookup(type, name);
                if (!TERRAIN_MACROS.contains(name)) found.add(name);
            }
        }
        List<String> ordered = new ArrayList<>();
        for (Uniform uniform : ALL) {
            if (found.contains(uniform.name)) ordered.add(uniform.name);
        }
        return ordered;
    }

    private static List<String> names(final String declarators) throws PackException {
        List<String> result = new ArrayList<>();
        for (String part : declarators.split(",")) {
            String name = part.strip();
            if (name.contains("[")) throw new PackException("uniform arrays are not supported: " + name);
            result.add(name);
        }
        return result;
    }

    private static Uniform lookup(final String type, final String name) throws PackException {
        Uniform uniform = BY_NAME.get(name);
        if (uniform == null && TERRAIN_MACROS.contains(name)) return new Uniform(name, type, true);
        if (uniform == null) {
            String later = LATER.get(name);
            if (later != null) throw new PackException("uniform " + name + " is an Iris uniform that is not provided by " + later);
            throw new PackException("uniform " + name + " is not a standard Iris uniform this version knows (custom uniforms are not supported yet)");
        }
        if (!uniform.type.equals(type)) throw new PackException("uniform " + name + " is declared " + type + ", but it is a " + uniform.type);
        return uniform;
    }

    /**
     * The source with its loose declarations of standard uniforms removed and the block put after the {@code #version} and
     * {@code #extension} lines. The block always holds every member, so one buffer fits every program; the ones this program
     * did not declare get names no program will use. Nothing changes when the program declares none.
     */
    public static String rewrite(final String source, final List<String> declared) throws PackException {
        if (declared.isEmpty()) return source;
        // Declarations are replaced by nothing, but their lines stay, so error messages keep their line numbers.
        Matcher match = LOOSE.matcher(source);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            String type = match.group(2);
            if (type.startsWith("sampler") || type.startsWith("image")) {
                match.appendReplacement(out, Matcher.quoteReplacement(match.group()));
                continue;
            }
            boolean block = false;
            for (String name : names(match.group(3))) {
                lookup(type, name);
                block |= !TERRAIN_MACROS.contains(name);
            }
            match.appendReplacement(out, block ? "" : Matcher.quoteReplacement(match.group()));
        }
        match.appendTail(out);
        String stripped = out.toString();

        StringBuilder block = new StringBuilder("layout(std140) uniform ").append(BLOCK).append(" {\n");
        for (Uniform uniform : ALL) {
            block.append("    ").append(uniform.type).append(' ').append(declared.contains(uniform.name) ? uniform.name : "_mx_unused_" + uniform.name).append(";\n");
        }
        block.append("};\n");

        // After the last #version or #extension line.
        int at = 0;
        Matcher header = HEADER_LINE.matcher(stripped);
        while (header.find()) at = header.end();
        return stripped.substring(0, at) + "\n" + block + stripped.substring(at);
    }

    /** Where each member of the block sits, and how big the block is. */
    public record Layout(Map<String, Integer> offsets, int size) {
    }

    private static final Layout LAYOUT = computeLayout();

    /** The {@code std140} layout of the block: each member starts at a multiple of its alignment, and a float may follow a vec3 in its last four bytes. */
    public static Layout layout() {
        return LAYOUT;
    }

    private static Layout computeLayout() {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int offset = 0;
        for (Uniform uniform : ALL) {
            offset = align(offset, alignment(uniform.type));
            offsets.put(uniform.name, offset);
            offset += size(uniform.type);
        }
        return new Layout(offsets, align(offset, 16));
    }

    static int alignment(final String type) {
        return switch (type) {
            case "float", "int" -> 4;
            case "vec2", "ivec2" -> 8;
            default -> 16;
        };
    }

    static int size(final String type) {
        return switch (type) {
            case "float", "int" -> 4;
            case "vec2", "ivec2" -> 8;
            case "vec3", "ivec3" -> 12;
            case "vec4", "ivec4" -> 16;
            case "mat4" -> 64;
            case "mat3" -> 48;
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static int align(final int offset, final int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    public static @Nullable Uniform find(final String name) {
        return BY_NAME.get(name);
    }
}
