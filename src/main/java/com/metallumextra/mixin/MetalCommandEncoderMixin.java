package com.metallumextra.mixin;

import com.metallum.render.MetallumExtraEncoder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallumextra.FrameProfiler;
import com.metallumextra.GpuTimed;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Time the CPU spends blocked waiting for the GPU to finish older frames (or fences), and what the shader pipeline
 * needs to know about the frame being recorded.
 */
@Mixin(targets = "com.metallum.render.MetalCommandEncoder", remap = false)
public abstract class MetalCommandEncoderMixin {
    /** Every buffer/texture upload or copy goes through here (Metallum gives each one its own copy pass). */
    @Inject(method = "blitCommandEncoder", at = @At("HEAD"))
    private void metallumExtra$uploadStarted(final CallbackInfoReturnable<?> cir) {
        FrameProfiler.uploadStarted();
    }

    @Inject(method = "awaitSubmitCompletion", at = @At("HEAD"))
    private void metallumExtra$gpuWaitBegin(final long submitIndex, final long timeoutMs, final CallbackInfoReturnable<Boolean> cir) {
        FrameProfiler.gpuWaitBegin();
    }

    @Inject(method = "awaitSubmitCompletion", at = @At("RETURN"))
    private void metallumExtra$gpuWaitEnd(final long submitIndex, final long timeoutMs, final CallbackInfoReturnable<Boolean> cir) {
        FrameProfiler.gpuWaitEnd();
    }

    /** The command buffer of a finished frame is let go of here, with its timestamps final. */
    @WrapOperation(method = "submit",
            at = @At(value = "INVOKE", target = "Lio/github/kokodio/metaljvm/metal/MTLCommandBuffer;release()V"))
    private void metallumExtra$readGpuTimes(final MTLCommandBuffer buffer, final Operation<Void> original) {
        ((GpuTimed) buffer).metallumExtra$readGpuTimes();
        original.call(buffer);
    }

    /** Compute work records into the command buffer of the frame being drawn, which it learns of here. */
    @Inject(method = "createRenderPass", at = @At("HEAD"))
    private void metallumExtra$frameRecorded(final RenderPassDescriptor descriptor, final CallbackInfoReturnable<RenderPassBackend> cir) {
        MetallumExtraEncoder.capture(this);
    }
}
