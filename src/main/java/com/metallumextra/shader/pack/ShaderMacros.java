package com.metallumextra.shader.pack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The names a shader pack's files may test with {@code #ifdef} and {@code #if}, which the loader defines the way Iris does: the Minecraft version, the
 * operating system, the GPU's vendor and the settings of the game. They are defined in every program (see {@link ShaderPack#load}) and in
 * {@code shaders.properties} (see {@link PropertiesFile}), so a pack decides the same thing in both.
 * <p>
 * It is Metal on macOS, so {@code MC_OS_MAC} and {@code MC_GL_VENDOR_APPLE}. {@code IS_IRIS} and {@code IRIS_VERSION} are defined too: this runs packs of the
 * Iris layout with Iris's uniforms (including {@code renderStage}), and a pack that does not find them treats the game as OptiFine (Complementary shows
 * "Colored Lighting is not supported on Optifine"). The version is one without the known bugs packs work around.
 */
public final class ShaderMacros {
    private ShaderMacros() {
    }

    /** The names and values, in the order they are written. */
    public static Map<String, String> builtin() {
        Map<String, String> macros = new LinkedHashMap<>();
        // The values of biome_category, as Iris names them.
        String[] categories = {"NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER", "UNDERGROUND", "MOUNTAIN"};
        for (int i = 0; i < categories.length; i++) macros.put("CAT_" + categories[i], String.valueOf(i));
        macros.put("MC_VERSION", "26020");
        macros.put("IS_IRIS", "");
        macros.put("IRIS_VERSION", "11100");
        macros.put("MC_GL_VERSION", "410");
        macros.put("MC_GLSL_VERSION", "330");
        macros.put("MC_OS_MAC", "");
        macros.put("MC_GL_VENDOR_APPLE", "");
        macros.put("MC_GL_RENDERER_APPLE", "");
        macros.put("MC_RENDER_QUALITY", "1.0");
        macros.put("MC_SHADOW_QUALITY", "1.0");
        macros.put("MC_HAND_DEPTH", "0.125");
        // Iris defines these always: the pack may read the resource pack's normal and specular maps (this loader gives it flat ones, see IrisPlan).
        macros.put("MC_NORMAL_MAP", "");
        macros.put("MC_SPECULAR_MAP", "");
        // The render stages, numbered as in Iris. The renderStage uniform is zero (none) until the game says which stage a draw belongs to.
        String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT",
                "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS",
                "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"};
        for (int i = 0; i < stages.length; i++) macros.put("MC_RENDER_STAGE_" + stages[i], Integer.toString(i));
        return macros;
    }

    /** The {@code #define} lines for a program. */
    public static String glsl() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> macro : builtin().entrySet()) {
            out.append("#define ").append(macro.getKey());
            if (!macro.getValue().isEmpty()) out.append(' ').append(macro.getValue());
            out.append('\n');
        }
        return out.toString();
    }
}
