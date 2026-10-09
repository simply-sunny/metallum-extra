package com.metallumextra;

import com.metallumextra.shader.pack.BuiltinOptions;

/**
 * Shader quality presets. A preset is a set of values for the built-in shader pack's options (see {@link BuiltinOptions}); picking one writes
 * them all, and the individual options stay free to change afterwards, at which point the preset reads as Custom. Medium is the default:
 * shadows and reflections, but no sun rays and a shorter shadow distance.
 */
public enum Quality {
    LOW("Low", 1024, 64, false, false, false),
    MEDIUM("Medium", 2048, 96, true, false, true),
    HIGH("High", 2048, 128, true, true, true),
    ULTRA("Ultra", 4096, 192, true, true, true),
    CUSTOM("Custom", 0, 0, false, false, false);

    public final String label;
    final int shadowResolution;
    final int shadowDistance;
    final boolean reflections;
    final boolean sunRays;
    final boolean ambientOcclusion;

    Quality(final String label, final int shadowResolution, final int shadowDistance, final boolean reflections, final boolean sunRays, final boolean ambientOcclusion) {
        this.label = label;
        this.shadowResolution = shadowResolution;
        this.shadowDistance = shadowDistance;
        this.reflections = reflections;
        this.sunRays = sunRays;
        this.ambientOcclusion = ambientOcclusion;
    }

    /** The preset the pack's options are, or Custom if they match none. */
    static Quality of() {
        for (Quality quality : values()) {
            if (quality == CUSTOM) continue;
            if (BuiltinOptions.on("SHADOWS") && Integer.parseInt(BuiltinOptions.value("shadowMapResolution")) == quality.shadowResolution
                    && Float.parseFloat(BuiltinOptions.value("shadowDistance")) == quality.shadowDistance
                    && BuiltinOptions.on("WATER_REFLECTIONS") == quality.reflections && BuiltinOptions.on("SUN_RAYS") == quality.sunRays
                    && BuiltinOptions.on("AMBIENT_OCCLUSION") == quality.ambientOcclusion && BuiltinOptions.on("BLOOM") && BuiltinOptions.on("WAVING")
                    && BuiltinOptions.on("COLORED_LIGHT")) {
                return quality;
            }
        }
        return CUSTOM;
    }

    /** Writes the preset's values into the options. */
    void apply() {
        BuiltinOptions.set("SHADOWS", true);
        BuiltinOptions.set("BLOOM", true);
        BuiltinOptions.set("WAVING", true);
        BuiltinOptions.set("COLORED_LIGHT", true);
        BuiltinOptions.setValue("shadowMapResolution", Integer.toString(shadowResolution));
        BuiltinOptions.setValue("shadowDistance", shadowDistance + ".0");
        BuiltinOptions.set("WATER_REFLECTIONS", reflections);
        BuiltinOptions.set("SUN_RAYS", sunRays);
        BuiltinOptions.set("AMBIENT_OCCLUSION", ambientOcclusion);
    }
}
