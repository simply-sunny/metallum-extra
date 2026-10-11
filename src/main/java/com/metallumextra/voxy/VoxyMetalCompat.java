package com.metallumextra.voxy;

import com.metallumextra.ExtraConfig;
import com.metallumextra.MetallumExtra;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Voxy's own renderer speaks raw OpenGL 4.6. Under Metallum's Metal backend the window is created
 * without an OpenGL context, so Voxy's very first GL call (its capability probe) would throw and take
 * the game down. This class records that situation and keeps the rest of Voxy alive: its world ingest,
 * storage, imports and config UI are all CPU-side, so they run normally with the OpenGL renderer
 * switched off. Drawing the LOD terrain itself is {@link MetalVoxyRenderer}'s job.
 */
public final class VoxyMetalCompat {
    /** Set instead of {@code VoxyClient.initVoxyClient()} when the active backend is Metal. */
    private static volatile boolean metalBackend;
    /** Voxy's instance factory installed by us; null until then. */
    private static volatile boolean factoryInstalled;

    private VoxyMetalCompat() {
    }

    /** True when the render device is Metallum's Metal backend. Safe to call at any time. */
    public static boolean isMetalBackend() {
        try {
            return "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Runs instead of Voxy's OpenGL initialisation: record that Voxy cannot use its renderer here and
     * install its per-world instance factory ourselves (Voxy only installs it when its GL probe passed),
     * so that chunk ingest, storage and imports keep working.
     */
    public static void onMetalBackend() {
        if (metalBackend) {
            return;
        }
        metalBackend = true;
        if (!factoryInstalled && !VoxyCommon.isAvailable()) {
            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
            factoryInstalled = true;
        }
        MetallumExtra.LOGGER.info("[Metallum Extra] Voxy found: its OpenGL renderer cannot run on Metal and is disabled{}",
                enabled() ? "; LOD terrain will be drawn through Metal" : " (compat.voxyMetalRenderer=false)");
    }

    /** Whether the Metal-side Voxy renderer is switched on. */
    public static boolean enabled() {
        return metalBackend && ExtraConfig.get().voxyMetalRenderer;
    }

    /** True once Voxy's init has been cancelled on Metal (mixins use this to know the state machine started). */
    public static boolean metalBackendSeen() {
        return metalBackend;
    }

    /** Whether Voxy is installed at all (the mixin config is gated on this too). */
    public static boolean voxyInstalled() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }
}
