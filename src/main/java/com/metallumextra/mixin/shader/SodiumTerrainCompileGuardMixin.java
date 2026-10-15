package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallumextra.shader.PipelineErrors;
import com.metallumextra.shader.Shaders;
import com.metallumextra.shader.pack.PackManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shaders: Sodium compiles the pipeline of a terrain layer when it draws it. If the pack's shader for that layer does not compile, the pack is put aside instead
 * of the frame failing; the pipeline is then compiled again, and since the pack is gone it gets Sodium's own shader (see {@code ShaderSources}).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class SodiumTerrainCompileGuardMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline metallumExtra$compileGuard(final RenderPipeline pipeline, final Operation<CompiledRenderPipeline> original) {
        try {
            return original.call(pipeline);
        } catch (RuntimeException e) {
            if (!Shaders.active() || !PackManager.fail(PipelineErrors.explain(e))) throw e;
            return original.call(pipeline);
        }
    }
}
