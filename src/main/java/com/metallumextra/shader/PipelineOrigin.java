package com.metallumextra.shader;

import com.google.common.collect.MapMaker;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Which {@link RenderPipeline} a compiled pipeline was made from. The game's pipeline cache goes from the one to the other; the render pass
 * (and Metallum's, which only sees the backend's half of it) only gets the compiled one back.
 */
public final class PipelineOrigin {
    /** Keyed by identity, and weakly, so a pipeline that is closed and dropped by the cache is not kept alive here. */
    private static final Map<Object, RenderPipeline> ORIGINS = new MapMaker().weakKeys().makeMap();

    private PipelineOrigin() {
    }

    public static void record(final CompiledRenderPipeline compiled, final RenderPipeline pipeline) {
        if (compiled == null || pipeline == null) return;
        ORIGINS.put(compiled, pipeline);
        if (compiled instanceof FrontendRenderPipeline frontend) ORIGINS.put(frontend.backendRenderPipeline(), pipeline);
    }

    /** The pipeline a compiled one (or the backend's half of it) was made from; null if the cache never said. */
    public static @Nullable RenderPipeline of(final Object compiled) {
        return ORIGINS.get(compiled);
    }
}
