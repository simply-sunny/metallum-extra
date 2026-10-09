package com.metallumextra.shader.sodium;

import com.metallumextra.mixin.shader.SodiumTerrainPrograms;

/** Sodium keeps one pipeline per layer of terrain, for good; a standard pack changes what they are made of, so they are made again. */
public final class SodiumTerrain {
    private SodiumTerrain() {
    }

    public static void resetPipelines() {
        try {
            SodiumTerrainPrograms.metallumExtra$programs().clear();
        } catch (RuntimeException | LinkageError e) {
            // Sodium not loaded, or a version without this map: nothing to reset.
        }
    }
}
