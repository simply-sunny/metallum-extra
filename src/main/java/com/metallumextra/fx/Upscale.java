package com.metallumextra.fx;

import com.metallum.render.MetallumExtraUpscale;
import com.metallumextra.ExtraConfig;
import com.metallumextra.MetallumExtra;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * MetalFX upscaling. While the world is drawn the game's image is a smaller one (see the {@code GameRenderer} mixin, which hands it out in place of the
 * window-sized image); when the world is done, MetalFX scales that to the window-sized image, which the menus and the HUD are then drawn onto.
 * <p>
 * Anything that fails turns the upscaling off for the rest of the session with a line in the log and the chat, and the frame is drawn at full size.
 */
public final class Upscale {
    private static @Nullable TextureTarget target;
    private static boolean inWorld;
    private static boolean failed;
    private static @Nullable String unsupported;
    private static boolean checked;
    private static long lastReport;
    private static String status = "off";

    private Upscale() {
    }

    private static final java.lang.reflect.Field[] FIELDS = fields();

    /**
     * What a render target is made of: its size and its textures with their views. While the world is drawn the game's own image holds the smaller
     * image's (see {@link #swap}), so everything that keeps a reference to the game's image (the sky, the clouds, the passes) draws at the smaller size.
     */
    private static java.lang.reflect.Field[] fields() {
        try {
            String[] names = {"width", "height", "colorTexture", "colorTextureView", "depthTexture", "depthTextureView"};
            java.lang.reflect.Field[] fields = new java.lang.reflect.Field[names.length];
            for (int i = 0; i < names.length; i++) {
                fields[i] = RenderTarget.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
            }
            return fields;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] MetalFX: cannot reach the parts of a render target", e);
            return null;
        }
    }

    private static void swap(final RenderTarget a, final RenderTarget b) {
        try {
            for (java.lang.reflect.Field field : FIELDS) {
                Object x = field.get(a);
                field.set(a, field.get(b));
                field.set(b, x);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One line for the debug screen. */
    public static String status() {
        return status;
    }

    private static boolean wanted() {
        UpscaleMode mode = ExtraConfig.get().effectiveUpscaleMode();
        if (mode == UpscaleMode.OFF) {
            status = "off";
            return false;
        }
        if (failed) return false;
        if (!checked) {
            // Needs a frame to have been recorded (the scaler is asked about the GPU of the command buffer).
            unsupported = MetallumExtraUpscale.unsupportedReason();
            if (MetallumExtraUpscale.PENDING.equals(unsupported)) return false;
            checked = true;
            if (unsupported != null) fail("MetalFX is not available: " + unsupported);
        }
        return !failed;
    }

    /** At the start of the world's drawing: switches the game's image for the smaller one. */
    public static void begin(final RenderTarget full) {
        // A frame that ended without the world finishing leaves the images swapped.
        if (inWorld && target != null) swap(full, target);
        inWorld = false;
        if (FIELDS == null) failed = true;
        if (!wanted()) {
            release();
            return;
        }
        UpscaleMode mode = ExtraConfig.get().effectiveUpscaleMode();
        int width = Math.max(1, Math.round(full.width * mode.scale));
        int height = Math.max(1, Math.round(full.height * mode.scale));
        if (target == null) {
            target = new TextureTarget("Metallum Extra world", width, height, full.getColorTexture().getFormat(), full.getDepthTexture().getFormat());
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
            MetallumExtraUpscale.release();
        }
        // The game cleared the full-size image at the start of the frame; the smaller one starts the same way.
        var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        org.joml.Vector4fc clear = MetallumExtraUpscale.pendingClear(full.getColorTexture());
        encoder.clearColorAndDepthTextures(target.getColorTexture(), clear != null ? clear : new org.joml.Vector4f(0, 0, 0, 0), target.getDepthTexture(), 1.0);
        // Drawn now, not left for the first pass that happens to use the image: it must cover all of it.
        MetallumExtraUpscale.flushClears(target.getColorTexture(), target.getDepthTexture());
        swap(full, target);
        inWorld = true;
    }

    /** At the end of the world's drawing: scales the smaller image into the window-sized one. */
    public static void end(final RenderTarget full) {
        if (!inWorld) return;
        inWorld = false;
        TextureTarget small = target;
        if (small == null) return;
        swap(full, small);
        String problem = MetallumExtraUpscale.encodeSpatial(small.getColorTextureView(), full.getColorTextureView(), small.width, small.height, full.width, full.height);
        if (problem != null) {
            fail(problem);
            return;
        }
        status = "MetalFX spatial " + small.width + "x" + small.height + " -> " + full.width + "x" + full.height;
    }

    private static void fail(final String reason) {
        failed = true;
        status = "off (" + reason + ")";
        MetallumExtra.LOGGER.error("[Metallum Extra] MetalFX upscaling is switched off: {}", reason);
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != null) minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Metallum Extra] MetalFX upscaling is off: " + reason));
        release();
    }

    private static void release() {
        inWorld = false;
        if (target != null) {
            target.destroyBuffers();
            target = null;
            MetallumExtraUpscale.release();
        }
    }
}
