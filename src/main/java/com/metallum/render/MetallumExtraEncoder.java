package com.metallum.render;

/** The command encoder frames are recorded with. Lives in Metallum's package to reach the encoder and its command buffer. */
public final class MetallumExtraEncoder {
    private static MetalCommandEncoder encoder;

    private MetallumExtraEncoder() {
    }

    /** The encoder frames are recorded with, once a pass has been made; null before. */
    static MetalCommandEncoder encoder() {
        return encoder;
    }

    /** Remembers the encoder frames are recorded with. Called as passes are created. */
    public static void capture(final Object commandEncoder) {
        encoder = (MetalCommandEncoder) commandEncoder;
    }
}
