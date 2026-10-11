package com.metallumextra.voxy;

import com.metallumextra.MetallumExtra;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.world.level.Level;

/**
 * Owns the single {@link MetalVoxyRenderer}, mirroring the lifecycle Voxy gives its own renderer:
 * {@code LevelExtractor.setLevel} swaps the world, {@code LevelExtractor.allChanged} tears the old
 * renderer down and makes a new one, and closing the level renderer shuts it down.
 *
 * <p>Voxy's per-world instance may not exist yet when the level is set (it is created when the session
 * starts), so creation is retried from {@link #tick()} until it appears.</p>
 */
public final class MetalVoxyRendererManager {
    private static MetalVoxyRenderer renderer;
    private static Level level;
    private static boolean pendingCreate;

    private MetalVoxyRendererManager() {
    }

    /** {@code LevelExtractor.setLevel}: the level changed (null on disconnect). */
    public static synchronized void onSetLevel(final Level newLevel) {
        if (level != newLevel) {
            shutdown();
            level = newLevel;
        }
    }

    /** {@code LevelExtractor.allChanged}: resources or the world reloaded, rebuild from scratch. */
    public static synchronized void onAllChanged() {
        shutdown();
        tryCreate();
    }

    /** {@code LevelRenderer.close}: the renderer itself is going away. */
    public static synchronized void onClose() {
        shutdown();
    }

    /** Once per frame: finish deferred creation and let the renderer work. */
    public static synchronized void tick() {
        if (pendingCreate && renderer == null) {
            tryCreate();
        }
        if (renderer != null) {
            renderer.tick();
        }
    }

    public static synchronized MetalVoxyRenderer get() {
        return renderer;
    }

    private static void tryCreate() {
        pendingCreate = false;
        if (renderer != null || level == null || !VoxyMetalCompat.enabled()) {
            return;
        }
        if (VoxyCommon.getInstance() == null) {
            // Voxy's instance (and with it the world engine) is created when the session starts.
            pendingCreate = true;
            return;
        }
        try {
            renderer = new MetalVoxyRenderer(level);
        } catch (final RuntimeException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] Could not create the Metal Voxy renderer", e);
        }
    }

    private static void shutdown() {
        pendingCreate = false;
        if (renderer != null) {
            try {
                renderer.shutdown();
            } catch (final RuntimeException e) {
                MetallumExtra.LOGGER.error("[Metallum Extra] Could not shut down the Metal Voxy renderer", e);
            }
            renderer = null;
        }
    }
}
