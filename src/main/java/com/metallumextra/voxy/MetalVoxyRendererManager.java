package com.metallumextra.voxy;

import com.metallumextra.MetallumExtra;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * Owns the single {@link MetalVoxyRenderer}, mirroring the lifecycle Voxy gives its own renderer:
 * {@code LevelExtractor.setLevel} swaps the world, {@code LevelExtractor.allChanged} tears the old
 * renderer down and makes a new one, and closing the level renderer shuts it down.
 *
 * <p>Voxy's per-world instance may not exist yet when the level is set (it is created when the session
 * starts), so creation is retried from {@link #frame} until it appears. Each frame is also the one
 * place that keeps a failing renderer from taking the game down with it: three consecutive frames
 * that throw shut the renderer off until the next world or resource reload.</p>
 */
public final class MetalVoxyRendererManager {
    /** Consecutive frames that may fail before the renderer is shut off for the rest of the session. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static MetalVoxyRenderer renderer;
    private static Level level;
    private static boolean pendingCreate;
    private static boolean disabled;
    private static int failures;

    private MetalVoxyRendererManager() {
    }

    /** {@code LevelExtractor.setLevel}: the level changed (null on disconnect). */
    public static synchronized void onSetLevel(final Level newLevel) {
        if (level != newLevel) {
            shutdown();
            disabled = false;
            level = newLevel;
        }
    }

    /** {@code LevelExtractor.allChanged}: resources or the world reloaded, rebuild from scratch. */
    public static synchronized void onAllChanged() {
        shutdown();
        disabled = false;
        tryCreate();
    }

    /** {@code LevelRenderer.close}: the renderer itself is going away. */
    public static synchronized void onClose() {
        shutdown();
    }

    /**
     * Once per frame, from the level renderer: finish deferred creation, hand the frame's camera
     * state over and draw. The camera render state carries the projection, the fog values and the
     * light the game settled on for this frame, which is everything this renderer needs from it.
     */
    public static synchronized void frame(final CameraRenderState camera) {
        if (disabled) {
            return;
        }
        if (pendingCreate && renderer == null) {
            tryCreate();
        }
        if (renderer == null) {
            return;
        }
        try {
            renderer.tick(camera);
            renderer.render(camera);
            failures = 0;
        } catch (final RuntimeException | LinkageError failure) {
            failures++;
            MetallumExtra.LOGGER.error("[Metallum Extra] Metal Voxy renderer failed on frame {} of {}",
                    failures, MAX_CONSECUTIVE_FAILURES, failure);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                // Rendering the LOD field is optional; taking the game down with it is not an option.
                disabled = true;
                shutdown();
            }
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
        failures = 0;
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
