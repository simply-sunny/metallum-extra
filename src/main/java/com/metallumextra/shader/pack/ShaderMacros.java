package com.metallumextra.shader.pack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The names a shader pack's files may test with {@code #ifdef} and {@code #if}, which the loader defines the way Iris does: the Minecraft version, the
 * operating system, the GPU's vendor and the settings of the game. They are defined in every program (see {@link ShaderPack#load}) and in
 * {@code shaders.properties} (see {@link PropertiesFile}), so a pack decides the same thing in both.
 * <p>
 * Only what is true of this renderer is defined: it is Metal on macOS, so {@code MC_OS_MAC} and {@code MC_GL_VENDOR_APPLE}, and no {@code IS_IRIS},
 * since this is not Iris and a pack that tests for it may then expect uniforms (such as {@code renderStage}) that are not provided.
 */
public final class ShaderMacros {
    private ShaderMacros() {
    }

    /** The names and values, in the order they are written. */
    public static Map<String, String> builtin() {
        Map<String, String> macros = new LinkedHashMap<>();
        macros.put("MC_VERSION", "26020");
        macros.put("MC_GL_VERSION", "410");
        macros.put("MC_GLSL_VERSION", "330");
        macros.put("MC_OS_MAC", "");
        macros.put("MC_GL_VENDOR_APPLE", "");
        macros.put("MC_GL_RENDERER_APPLE", "");
        macros.put("MC_RENDER_QUALITY", "1.0");
        macros.put("MC_SHADOW_QUALITY", "1.0");
        macros.put("MC_HAND_DEPTH", "0.125");
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
