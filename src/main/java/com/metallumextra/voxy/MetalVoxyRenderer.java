package com.metallumextra.voxy;

import com.metallumextra.MetallumExtra;
import net.minecraft.world.level.Level;

/**
 * Draws Voxy's LOD terrain through Metal: Voxy's CPU side (world ingest, node building, section
 * meshing, model baking) is reused as-is, and its OpenGL drawing is replaced by a renderpearl pipeline.
 *
 * <p>Lifecycle only for now; the engine wiring, section upload and draw pass attach here.</p>
 */
public final class MetalVoxyRenderer {
    private final Level level;

    public MetalVoxyRenderer(final Level level) {
        this.level = level;
        MetallumExtra.LOGGER.info("[Metallum Extra] Metal Voxy renderer created for {}", level.dimension());
    }

    public void tick() {
    }

    public void shutdown() {
        MetallumExtra.LOGGER.info("[Metallum Extra] Metal Voxy renderer shut down");
    }

    public Level level() {
        return this.level;
    }
}
