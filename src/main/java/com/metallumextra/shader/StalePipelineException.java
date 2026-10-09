package com.metallumextra.shader;

/**
 * A pipeline asked for the shader of a program in a pack that is no longer in use: the pack was put aside while its pipeline
 * was being built, and the pipeline is being tried again against the pack that replaced it. Not the replacement's fault.
 */
public final class StalePipelineException extends IllegalStateException {
    public StalePipelineException(final String message) {
        super(message);
    }
}
