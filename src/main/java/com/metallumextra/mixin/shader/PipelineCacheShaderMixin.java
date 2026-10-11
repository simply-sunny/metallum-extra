package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.sugar.Local;
import com.metallumextra.shader.PipelineOrigin;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shaders: remembers which pipeline each compiled one was made from (see {@link PipelineOrigin}). */
@Mixin(PipelineCache.class)
public abstract class PipelineCacheShaderMixin {
    @Inject(method = "get", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private void metallumExtra$compiled(final RenderPipeline pipeline, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<CompiledRenderPipeline> cir,
                                        @Local(ordinal = 1) final CompiledRenderPipeline newPipeline) {
        PipelineOrigin.record(newPipeline, pipeline);
    }

    @Inject(method = "insert", at = @At("HEAD"))
    private void metallumExtra$inserted(final RenderPipeline pipeline, final CompiledRenderPipeline compiled, final CallbackInfo ci) {
        PipelineOrigin.record(compiled, pipeline);
    }
}
