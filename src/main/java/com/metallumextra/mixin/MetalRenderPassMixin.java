package com.metallumextra.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallumextra.FrameProfiler;
import com.metallumextra.SamplerSlots;
import com.metallumextra.shader.PipelineOrigin;
import com.metallumextra.shader.ShaderBindings;
import com.metallumextra.shader.Shaders;
import com.metallum.render.MetallumExtraBridge;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import io.github.kokodio.metaljvm.metal.MTLRenderCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLRenderPipelineState;
import io.github.kokodio.metaljvm.metal.MTLSamplerState;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Profiler: how many terrain draws Metallum issues and how long that takes. Metal has no multi-draw, so
 * Metallum sends each draw in a batch as its own call; these numbers size what a stored draw list could save.
 * <p>
 * Shaders: a pipeline whose shaders read the shader pipeline's own uniform block or textures is handed them
 * here, since whoever is drawing with it does not know they exist (see {@link ShaderBindings}).
 */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassMixin {
    @Unique
    private long metallumExtra$batchStart;

    /** Shaders: draws with the pipeline that is set now are dropped (see {@link ShaderBindings#skipsDraws}). */
    @Unique
    private boolean metallumExtra$skipDraws;

    /** The sampler renumbering of the pipeline that is bound now; null for nearly every pipeline. */
    @Unique
    private byte @Nullable [] metallumExtra$samplerSlots;

    @Shadow
    private static void bindTextureAndSampler(final MTLRenderCommandEncoder encoder, final MTLTexture texture, final MTLSamplerState sampler, final long index, final int stageMask) {
        throw new AssertionError();
    }

    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void metallumExtra$pipelineSet(final BackendRenderPipeline pipeline, final CallbackInfo ci) {
        RenderPipeline origin = Shaders.active() ? PipelineOrigin.of(pipeline) : null;
        this.metallumExtra$skipDraws = origin != null && ShaderBindings.skipsDraws(origin);
    }

    @Inject(method = {"draw", "drawIndexed", "drawIndirect", "drawIndexedIndirect", "multiDraw", "multiDrawIndexed"}, at = @At("HEAD"), cancellable = true)
    private void metallumExtra$skipDraw(final CallbackInfo ci) {
        if (this.metallumExtra$skipDraws) ci.cancel();
    }

    @Inject(method = "drawIndexedIndirect", at = @At("HEAD"))
    private void metallumExtra$batchBegin(final GpuBufferSlice commands, final int drawCount, final CallbackInfo ci) {
        if (FrameProfiler.enabled()) this.metallumExtra$batchStart = System.nanoTime();
    }

    @Inject(method = "drawIndexedIndirect", at = @At("RETURN"))
    private void metallumExtra$batchEnd(final GpuBufferSlice commands, final int drawCount, final CallbackInfo ci) {
        if (FrameProfiler.enabled()) FrameProfiler.drawBatchSubmitted(drawCount, System.nanoTime() - this.metallumExtra$batchStart);
    }

    /** This is where the pass learns which pipeline it is about to bind resources for. */
    @WrapOperation(method = "bindDrawState",
            at = @At(value = "INVOKE", target = "Lcom/metallum/render/MetalCompiledRenderPipeline;getNativePipeline(I)Lio/github/kokodio/metaljvm/metal/MTLRenderPipelineState;"))
    private MTLRenderPipelineState metallumExtra$pipelineBound(final @Coerce Object pipeline, final int layout, final Operation<MTLRenderPipelineState> original) {
        this.metallumExtra$samplerSlots = ((SamplerSlots.Pipeline) pipeline).metallumExtra$samplerSlots();
        long shaderBindings = ((ShaderBindings.Pipeline) pipeline).metallumExtra$shaderBindings();
        if (shaderBindings != 0) ShaderBindings.bind((RenderPassBackend) (Object) this, name -> MetallumExtraBridge.uniformIndex(pipeline, name), shaderBindings);
        return original.call(pipeline, layout);
    }

    /** Metallum binds a texture and its sampler under the same number; a renumbered shader expects the sampler elsewhere. */
    @Redirect(method = "pushDescriptor",
            at = @At(value = "INVOKE", target = "Lcom/metallum/render/MetalRenderPass;bindTextureAndSampler(Lio/github/kokodio/metaljvm/metal/MTLRenderCommandEncoder;Lio/github/kokodio/metaljvm/metal/MTLTexture;Lio/github/kokodio/metaljvm/metal/MTLSamplerState;JI)V"))
    private void metallumExtra$bindWithSamplerSlot(final MTLRenderCommandEncoder encoder, final MTLTexture bound, final MTLSamplerState sampler, final long index, final int stageMask) {
        // A texture the game has no kind for (a 3D one) is a placeholder for the real texture, which is what gets bound.
        MTLTexture texture = com.metallum.render.MetallumExtraNative.resolve(bound);
        byte[] slots = this.metallumExtra$samplerSlots;
        if (slots == null) {
            bindTextureAndSampler(encoder, texture, sampler, index, stageMask);
            return;
        }
        if ((stageMask & SamplerSlots.STAGE_VERTEX) != 0) {
            encoder.setVertexTexture(texture, index);
            encoder.setVertexSamplerState(sampler, slots[(int) index * 2]);
        }
        if ((stageMask & SamplerSlots.STAGE_FRAGMENT) != 0) {
            encoder.setFragmentTexture(texture, index);
            encoder.setFragmentSamplerState(sampler, slots[(int) index * 2 + 1]);
        }
    }
}
