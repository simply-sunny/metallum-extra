package com.metallumextra.fx;

/** How much smaller than the window the world is drawn before MetalFX scales it up (the menus and the HUD are always drawn at the window's size). */
public enum UpscaleMode {
    OFF("Off", 1.0F),
    QUALITY("Quality (1.5x)", 1.0F / 1.5F),
    BALANCED("Balanced (1.7x)", 1.0F / 1.7F),
    PERFORMANCE("Performance (2x)", 0.5F),
    ULTRA_PERFORMANCE("Ultra Performance (3x)", 1.0F / 3.0F);

    public final String label;
    /** The world's width and height as a share of the window's. */
    public final float scale;

    UpscaleMode(final String label, final float scale) {
        this.label = label;
        this.scale = scale;
    }

    public static UpscaleMode of(final String name) {
        try {
            return valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
