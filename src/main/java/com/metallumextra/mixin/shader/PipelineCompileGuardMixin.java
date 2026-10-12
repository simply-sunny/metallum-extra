package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallumextra.shader.PipelineErrors;
import com.metallumextra.shader.Shaders;
import com.metallumextra.shader.pack.PackManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Shaders: a pipeline is compiled the first time something draws with it (the terrain, the sky, the shadow pass, the hand ...). If the pack's shader for it
 * does not compile, the pack is put aside instead of the frame failing; the pipeline is then compiled again, and since the pack is gone it gets the game's
 * own shader (see {@code ShaderSources}).
 */
@Mixin(RenderSystem.class)
public abstract class PipelineCompileGuardMixin {
    @WrapMethod(method = "getCompiledPipeline")
    private static CompiledRenderPipeline metallumExtra$compileGuard(final RenderPipeline pipeline, final Operation<CompiledRenderPipeline> original) {
        try {
            return original.call(pipeline);
        } catch (RuntimeException e) {
            if (!Shaders.active() || !PackManager.fail(PipelineErrors.explain(e))) throw e;
            return original.call(pipeline);
        }
    }
}
