package com.metallumextra.mixin;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A pipeline being built, as far as its colour targets go: a pipeline the game made is amended with the pack's buffers (see {@code IrisWorld#declareExtras}). */
@Mixin(RenderPipeline.Builder.class)
public interface RenderPipelineBuilderAccessor {
    @Accessor("colorTargetStates")
    ColorTargetState[] metallumExtra$colorTargetStates();
}
