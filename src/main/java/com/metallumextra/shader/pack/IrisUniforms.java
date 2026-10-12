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
            new Uniform("eyeAltitude", "float"), new Uniform("sunAngle", "float"), new Uniform("timeAngle", "float"), new Uniform("shadowAngle", "float"),
            new Uniform("rainStrength", "float"), new Uniform("wetness", "float"), new Uniform("thunderStrength", "float"),
            new Uniform("frameTime", "float"), new Uniform("frameTimeCounter", "float"),
            new Uniform("viewWidth", "float"), new Uniform("viewHeight", "float"), new Uniform("aspectRatio", "float"),
            new Uniform("screenBrightness", "float"), new Uniform("near", "float"), new Uniform("far", "float"),
            new Uniform("nightVision", "float"), new Uniform("blindness", "float"), new Uniform("blindFactor", "float"), new Uniform("darknessFactor", "float"),
            new Uniform("frameCounter", "int"), new Uniform("worldTime", "int"), new Uniform("worldDay", "int"),
            new Uniform("moonPhase", "int"), new Uniform("isEyeInWater", "int"),
            new Uniform("shadowModelView", "mat4"), new Uniform("shadowModelViewInverse", "mat4"),
            new Uniform("shadowProjection", "mat4"), new Uniform("shadowProjectionInverse", "mat4"),
            // Not Iris: the lighting model of the built-in shader pack (see ShaderGlobals). A pack that reads these runs here only.
            new Uniform("MxLightDir", "vec4"), new Uniform("MxLightColor", "vec4"), new Uniform("MxSunDir", "vec4"), new Uniform("MxMoonDir", "vec4"),
            new Uniform("MxSkyAmbient", "vec4"), new Uniform("MxBlockLight", "vec4"), new Uniform("MxMinAmbient", "vec4"),
            new Uniform("MxSkyZenith", "vec4"), new Uniform("MxSkyHorizon", "vec4"), new Uniform("MxSunsetColor", "vec4"),
            new Uniform("MxLightGrid", "vec4"), new Uniform("MxEyeSky", "float"),
            new Uniform("MxFlags", "vec4"), new Uniform("MxFog", "vec4"), new Uniform("MxFogEnds", "vec4"), new Uniform("MxFogColor", "vec4"),
            new Uniform("modelViewMatrix", "mat4", true), new Uniform("modelViewMatrixInverse", "mat4", true),
            new Uniform("projectionMatrix", "mat4", true), new Uniform("projectionMatrixInverse", "mat4", true),
            new Uniform("normalMatrix", "mat3", true),
            new Uniform("cameraPositionInt", "ivec3"), new Uniform("previousCameraPositionInt", "ivec3"),
            new Uniform("cameraPositionFract", "vec3"), new Uniform("previousCameraPositionFract", "vec3"), new Uniform("relativeEyePosition", "vec3"),
            new Uniform("lightningBoltPosition", "vec4"), new Uniform("playerMood", "float"), new Uniform("darknessLightFactor", "float"),
            new Uniform("fogStart", "float"), new Uniform("fogEnd", "float"),
            new Uniform("centerDepthSmooth", "float"), new Uniform("eyePosition", "vec3"), new Uniform("playerBodyVector", "vec3"), new Uniform("playerLookVector", "vec3"),
            new Uniform("isSpectator", "bool"), new Uniform("hideGUI", "bool"), new Uniform("firstPersonCamera", "bool"),
            new Uniform("fogDensity", "float"), new Uniform("fogShape", "int"), new Uniform("fogMode", "int"),
            new Uniform("biome", "int"), new Uniform("biome_category", "int"), new Uniform("biome_precipitation", "int"), new Uniform("rainfall", "float"), new Uniform("temperature", "float"),
            new Uniform("ambientLight", "float"), new Uniform("bedrockLevel", "int"), new Uniform("cloudHeight", "float"), new Uniform("hasCeiling", "bool"), new Uniform("hasSkylight", "bool"),
            new Uniform("heightLimit", "int"), new Uniform("logicalHeightLimit", "int"), new Uniform("constantMood", "float"),
            new Uniform("currentDate", "ivec3"), new Uniform("currentTime", "ivec3"), new Uniform("currentYearTime", "ivec2"), new Uniform("textureFilteringMode", "int"),
            new Uniform("endFlashPosition", "vec3"), new Uniform("endFlashIntensity", "float"), new Uniform("previousEndFlashIntensity", "float"),
            new Uniform("heldItemId", "int"), new Uniform("heldItemId2", "int"), new Uniform("heldBlockLightValue", "int"), new Uniform("heldBlockLightValue2", "int"),
            // What is being drawn right now: not one value for the frame, so the block holds the last one set (see IrisPipeline).
            new Uniform("entityId", "int", true), new Uniform("renderStage", "int", true), new Uniform("blockEntityId", "int", true), new Uniform("currentRenderedItemId", "int", true),
            new Uniform("entityColor", "vec4", true),
            // Not Iris: the table from the mesh's palette index to the id block.properties gave (see BlockIds). Programs do not declare it; the terrain's prelude uses it.
            new Uniform("MxBlockIds", "ivec4[128]"));

    private static final Map<String, Uniform> BY_NAME = new LinkedHashMap<>();
    /** The custom uniforms of the pack in use ({@code uniform.float.x = ...} in shaders.properties); every program's block holds them after the standard ones. */
    private static volatile Map<String, Uniform> custom = Map.of();
    private static volatile Layout customLayout;

    /** Makes the custom uniforms of the pack about to be planned known. Called when a plan is built; the block's layout follows. */
    public static synchronized void useCustom(final List<CustomUniforms.Def> defs) {
        Map<String, Uniform> map = new LinkedHashMap<>();
        for (CustomUniforms.Def def : defs) {
            if (!BY_NAME.containsKey(def.name())) map.put(def.name(), new Uniform(def.name(), def.type()));
        }
        custom = map;
        customLayout = computeLayout(allUniforms());
    }

    /** The pack's custom uniforms and the layout they make, to put back with {@link #restoreCustom}. */
    public static synchronized Object saveCustom() {
        return new Object[] {custom, customLayout};
    }

    @SuppressWarnings("unchecked")
    public static synchronized void restoreCustom(final Object saved) {
        Object[] state = (Object[]) saved;
        custom = (Map<String, Uniform>) state[0];
        customLayout = (Layout) state[1];
    }

    /** Changes with the pack's custom uniforms: programs read while one set was in use are not valid for another (see {@link LoadCache}). */
    static int customStamp() {
        return custom.keySet().hashCode();
    }

    /** Every uniform of the block: the standard ones, then the pack's own, in layout order. */
    public static List<Uniform> allUniforms() {
        List<Uniform> all = new ArrayList<>(ALL);
        all.addAll(custom.values());
        return all;
    }

    /** Iris uniforms this version does not provide yet, and why; asking for one is an error that says so. */
    private static final Map<String, String> LATER = new LinkedHashMap<>();

    static {
        for (Uniform uniform : ALL) BY_NAME.put(uniform.name, uniform);
        for (String name : List.of("blendFunc")) {
            LATER.put(name, "the gbuffers programs, which are not run yet");
        }
    }

    /** Names of the gbuffers programs' own uniforms that are not in the block: they are written into the source as expressions (see {@code IrisTerrain}). */
    public static final Set<String> TERRAIN_MACROS = Set.of("chunkOffset", "textureMatrix", "alphaTestRef", "atlasSize");

    private static final Pattern LOOSE = Pattern.compile("(?m)^([ \\t]*)uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+([\\w\\s,\\[\\]]+?)\\s*;[ \\t]*(//[^\\n]*)?$");
    private static final Pattern INITIALIZED = Pattern.compile("(?m)^([ \\t]*)uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(float|int|uint|bool|[iub]?vec[234]|mat[234])\\s+(\\w+)\\s*=\\s*([^;\\n]+);");
    private static final Pattern HEADER_LINE = Pattern.compile("(?m)^[ \\t]*(#version[^\\n]*|#extension[^\\n]*)$");

    private IrisUniforms() {
    }

    /**
     * A uniform that is neither standard nor one of the pack's own custom ones is never set by anything, which in Iris leaves it at zero; so its
     * declaration becomes a constant zero of the same type. (A name a program also uses for a local variable is then simply shadowed.)
     */
    public static String zeroUndefined(final String source) {
        // A uniform that starts with a value ("uniform bool isElytraFlying = false;") is nothing the game sets here: it is that value.
        String initialized = INITIALIZED.matcher(source).replaceAll("$1const $2 $3 = $4;");
        Matcher match = LOOSE.matcher(initialized);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            String type = match.group(2);
            String[] declarators = match.group(3).split(",");
            boolean opaque = type.matches("[iu]?sampler.*|image.*|[iu]image.*");
            boolean plain = type.matches("float|int|uint|bool|[iub]?vec[234]|mat[234]");
            boolean arrays = false;
            List<String> known = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String d : declarators) {
                String name = d.strip();
                if (name.contains("[")) arrays = true;
                else if (BY_NAME.containsKey(name) || custom.containsKey(name) || TERRAIN_MACROS.contains(name)) known.add(name);
                else unknown.add(name);
            }
            // In a list, only the Distant Horizons names are zero: any other unknown name may be a custom uniform, which is not known yet when the program is first read.
            boolean listWithOthers = declarators.length > 1 && unknown.stream().anyMatch(name -> !name.startsWith("dh"));
            if (opaque || !plain || arrays || unknown.isEmpty() || listWithOthers) {
                match.appendReplacement(out, Matcher.quoteReplacement(match.group()));
                continue;
            }
            // What the game does not set is zero: each such name becomes a constant, and the ones it does set stay declared together.
            String zero = type.startsWith("mat") ? type + "(0.0)" : type + "(0)";
            StringBuilder replacement = new StringBuilder();
            if (!known.isEmpty()) replacement.append(match.group(1)).append("uniform ").append(type).append(' ').append(String.join(", ", known)).append(";\n");
            for (String name : unknown) replacement.append(match.group(1)).append("const ").append(type).append(' ').append(name).append(" = ").append(zero).append(";\n");
            replacement.setLength(replacement.length() - 1);
            match.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        match.appendTail(out);
        return out.toString();
    }

    private static final Pattern WORLD_ONLY = Pattern.compile("(?m)^([ \\t]*)uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(int|vec4)\\s+(entityId|blockEntityId|currentRenderedItemId|entityColor|renderStage)\\s*;[ \\t]*$");

    /** What only the programs that draw the world are told (the id of the entity being drawn) is zero for the others, as in Iris. */
    public static String zeroWorldOnly(final String source) {
        return WORLD_ONLY.matcher(source).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "const " + m.group(2) + " " + m.group(3) + " = " + m.group(2) + "(0);"));
    }

    /** The standard uniforms a source declares, in layout order. Samplers are not uniforms in this sense. */
    public static List<String> declared(final String source) throws PackException {
        Set<String> found = new LinkedHashSet<>();
        Matcher match = LOOSE.matcher(source);
        while (match.find()) {
            String type = match.group(2);
            if (type.matches("[iu]?sampler.*|image.*")) continue;
            for (String name : names(match.group(3))) {
                lookup(type, name);
                if (!TERRAIN_MACROS.contains(name)) found.add(name);
            }
        }
        // The terrain's prelude reads the block id table without any program declaring it.
        if (BLOCK_IDS.matcher(source).find()) found.add("MxBlockIds");
        List<String> ordered = new ArrayList<>();
        for (Uniform uniform : allUniforms()) {
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

    private static boolean isScalar(final String type) {
        return type.equals("int") || type.equals("float") || type.equals("bool");
    }

    private static Uniform lookup(final String type, final String name) throws PackException {
        Uniform uniform = BY_NAME.get(name);
        if (uniform == null) uniform = custom.get(name);
        if (uniform == null && TERRAIN_MACROS.contains(name)) return new Uniform(name, type, true);
        if (uniform == null) {
            String later = LATER.get(name);
            if (later != null) throw new PackException("uniform " + name + " is an Iris uniform that is not provided by " + later);
            throw new PackException("uniform " + name + " is not a standard Iris uniform this version knows (and shaders.properties defines no custom uniform of that name)");
        }
        if (!uniform.type.equals(type) && !(isScalar(type) && isScalar(uniform.type))) throw new PackException("uniform " + name + " is declared " + type + ", but it is a " + uniform.type);
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
        // A program may declare a number as another kind than Iris holds it (an int for a bool): the name it reads is then a conversion of the member.
        Map<String, String> coerced = new LinkedHashMap<>();
        // The names this stage declares itself: the block is the same for both stages of a program, but a stage that does not declare a name may use it for something else.
        Set<String> here = new LinkedHashSet<>();
        Matcher match = LOOSE.matcher(source);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            String type = match.group(2);
            if (type.matches("[iu]?sampler.*|image.*")) {
                match.appendReplacement(out, Matcher.quoteReplacement(match.group()));
                continue;
            }
            boolean block = false;
            for (String name : names(match.group(3))) {
                here.add(name);
                Uniform found = lookup(type, name);
                if (!found.type.equals(type) && !TERRAIN_MACROS.contains(name)) coerced.put(name, type);
                block |= !TERRAIN_MACROS.contains(name);
            }
            match.appendReplacement(out, block ? "" : Matcher.quoteReplacement(match.group()));
        }
        match.appendTail(out);
        String stripped = out.toString();

        StringBuilder block = new StringBuilder("layout(std140) uniform ").append(BLOCK).append(" {\n");
        StringBuilder booleans = new StringBuilder();
        for (Uniform uniform : allUniforms()) {
            boolean isBool = uniform.type.equals("bool");
            boolean used = declared.contains(uniform.name) && (here.contains(uniform.name) || uniform.name.startsWith("Mx"));
            // SPIR-V has no boolean in a uniform block: it is held as an int, and the name the pack reads is that int compared with zero.
            String wanted = coerced.get(uniform.name);
            String name = isBool ? "_mx_bool_" + uniform.name : !used ? "_mx_unused_" + uniform.name : wanted != null ? "_mx_raw_" + uniform.name : uniform.name;
            block.append("    ").append(isBool ? "int" : uniform.type).append(' ').append(name).append(";\n");
            if (used) {
                String value = isBool ? "(" + name + " != 0)" : name;
                if (wanted != null) booleans.append("#define ").append(uniform.name).append(' ').append(wanted).append('(').append(value).append(")\n");
                else if (isBool) booleans.append("#define ").append(uniform.name).append(' ').append(value).append('\n');
            }
        }
        block.append("};\n").append(booleans);

        // After the last #version or #extension line.
        int at = LegacyGlsl.headerEnd(stripped);
        return stripped.substring(0, at) + "\n" + block + stripped.substring(at);
    }

    /** Where each member of the block sits, and how big the block is. */
    public record Layout(Map<String, Integer> offsets, int size) {
    }

    /** The {@code std140} layout of the block: each member starts at a multiple of its alignment, and a float may follow a vec3 in its last four bytes. */
    public static synchronized Layout layout() {
        if (customLayout == null) customLayout = computeLayout(allUniforms());
        return customLayout;
    }

    private static Layout computeLayout(final List<Uniform> uniforms) {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int offset = 0;
        for (Uniform uniform : uniforms) {
            offset = align(offset, alignment(uniform.type));
            offsets.put(uniform.name, offset);
            offset += size(uniform.type);
        }
        return new Layout(offsets, align(offset, 16));
    }

    static int alignment(final String type) {
        return switch (type) {
            case "float", "int", "bool" -> 4;
            case "vec2", "ivec2" -> 8;
            default -> 16;
        };
    }

    private static final Pattern BLOCK_IDS = Pattern.compile("\\bMxBlockIds\\b");

    static int size(final String type) {
        return switch (type) {
            case "float", "int", "bool" -> 4;
            case "vec2", "ivec2" -> 8;
            case "vec3", "ivec3" -> 12;
            case "vec4", "ivec4" -> 16;
            case "mat4" -> 64;
            case "mat3" -> 48;
            case "ivec4[128]" -> 2048;
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static int align(final int offset, final int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    public static @Nullable Uniform find(final String name) {
        Uniform uniform = BY_NAME.get(name);
        return uniform != null ? uniform : custom.get(name);
    }
}
