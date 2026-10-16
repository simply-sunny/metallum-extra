package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallumextra.shader.IrisWorld;
import com.metallumextra.shader.PipelineOrigin;
import com.metallumextra.shader.Shaders;
import com.metallumextra.shader.pack.PackManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Shaders: the game checks a pass's color attachments against the pipeline's color targets before the backend sees the pipeline, and a pass that
 * draws the world has more attachments than the game's own pipelines declare; so, while a pack draws the world, the pack's copy of the pipeline
 * is chosen here. If the copy cannot be compiled the pack is put aside, and the game's own pipeline is drawn with for the rest of this frame.
 */
@Mixin(FrontendRenderPass.class)
public abstract class FrontendRenderPassShaderMixin {
    @Shadow @Final private List<?> colorAttachments;

    /** Whether the draws with the pipeline set last belong to the other part of the held item's (see {@link Shaders#skipsHandDraw}). */
    @org.spongepowered.asm.mixin.Unique
    private boolean metallumExtra$skipDraws;

    @WrapMethod(method = "setPipeline")
    private void metallumExtra$packPipeline(final CompiledRenderPipeline pipeline, final Operation<Void> original) {
        RenderPipeline origin = PipelineOrigin.of(pipeline);
        this.metallumExtra$skipDraws = origin != null && Shaders.skipsHandDraw(origin);
        if (origin == null) {
            original.call(pipeline);
            return;
        }
        RenderPipeline copy = IrisWorld.pipelineFor(origin);
        // A pass the pack's buffers were not attached to (not the game's image) keeps the game's pipelines.
        if (copy == origin || copy.getColorTargetStates().size() != this.colorAttachments.size()) {
            original.call(pipeline);
            return;
        }
        try {
            original.call(RenderSystem.getCompiledPipeline(copy));
        } catch (RuntimeException e) {
            if (!Shaders.active() || !PackManager.fail(com.metallumextra.shader.PipelineErrors.explain(e))) throw e;
            // The pack's copy of the pipeline is the one that failed: the game's own is drawn with for the rest of this frame (with the pack's buffers declared, since the pass has them).
            RenderPipeline plain = IrisWorld.withoutPack(origin);
            original.call(plain == origin ? pipeline : RenderSystem.getCompiledPipeline(plain));
        }
    }

    @WrapMethod(method = "drawIndexed")
    private void metallumExtra$handDrawIndexed(final int baseVertex, final int firstIndex, final int indexCount, final int instanceCount, final int firstInstance, final Operation<Void> original) {
        if (!this.metallumExtra$skipDraws) original.call(baseVertex, firstIndex, indexCount, instanceCount, firstInstance);
    }

    @WrapMethod(method = "drawMultipleIndexed")
    private <T> void metallumExtra$handDrawMultiple(final java.util.Collection<com.mojang.renderpearl.api.commands.RenderPass.Draw<T>> draws, final com.mojang.renderpearl.api.buffers.GpuBuffer indexBuffer,
                                                   final com.mojang.renderpearl.api.pipeline.IndexType indexType, final java.util.Collection<String> dynamicUniforms, final T userData, final Operation<Void> original) {
        if (!this.metallumExtra$skipDraws) original.call(draws, indexBuffer, indexType, dynamicUniforms, userData);
    }

    @WrapMethod(method = "draw")
    private void metallumExtra$handDraw(final int firstVertex, final int vertexCount, final int instanceCount, final int firstInstance, final Operation<Void> original) {
        if (!this.metallumExtra$skipDraws) original.call(firstVertex, vertexCount, instanceCount, firstInstance);
    }
}
