package com.metallumextra.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game keeps its compiled pipelines in two caches, and has no way to throw them away. */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
    @Accessor("currentPipelineCache")
    static @Nullable PipelineCache metallumExtra$currentPipelineCache() {
        throw new AssertionError();
    }

    @Accessor("fallbackPipelineCache")
    static @Nullable PipelineCache metallumExtra$fallbackPipelineCache() {
        throw new AssertionError();
    }
}
