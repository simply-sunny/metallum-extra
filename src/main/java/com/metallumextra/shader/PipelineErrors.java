package com.metallumextra.shader;

import org.jspecify.annotations.Nullable;

/**
 * The game compiles a pipeline, logs what went wrong and hands back nothing, so what the shader compiler said is kept here for the moment the
 * missing pipeline is noticed, which is where a broken pack is put aside with the reason shown in the menu.
 */
public final class PipelineErrors {
    private static volatile @Nullable Throwable last;

    private PipelineErrors() {
    }

    public static void remember(final Throwable error) {
        last = error;
    }

    /** Throws {@code error} as it is, checked or not. */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException rethrow(final Throwable error) throws E {
        throw (E) error;
    }

    /** The error that stopped the pipeline in {@code failure}, if the shader compiler said one; else {@code failure} itself. */
    public static Throwable explain(final Throwable failure) {
        Throwable said = last;
        last = null;
        return said != null ? said : failure;
    }
}
