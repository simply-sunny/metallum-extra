package com.metallumextra.mixin;

import com.metallumextra.FrameProfiler;
import com.metallumextra.GpuTimed;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Count render/copy passes per frame (on Apple GPUs every pass switch costs a tile store + reload), and read back
 * Metal's own timestamps for each frame's command buffer: how long it waited to be scheduled and how long the GPU
 * actually spent executing it.
 */
@Mixin(value = MTLCommandBuffer.class, remap = false)
public abstract class MTLCommandBufferMixin implements GpuTimed {
    @Unique private long metallumExtra$commitNs;
    @Unique private long metallumExtra$frameStats;

    @Inject(method = "commit", at = @At("HEAD"))
    private void metallumExtra$committed(final CallbackInfo ci) {
        if (!FrameProfiler.enabled()) return;
        this.metallumExtra$commitNs = System.nanoTime();
        this.metallumExtra$frameStats = FrameProfiler.frameStatsForGpu();
    }

    /** Called when Metallum is done with a frame's command buffer, which it is only after the frame has completed: the timestamps are final. */
    @Override
    public void metallumExtra$readGpuTimes() {
        if (this.metallumExtra$commitNs == 0L) return;
        MTLCommandBuffer self = (MTLCommandBuffer) (Object) this;
        FrameProfiler.gpuFrameCompleted(this.metallumExtra$commitNs, this.metallumExtra$frameStats,
                self.kernelStartTime(), self.kernelEndTime(), self.GPUStartTime(), self.GPUEndTime());
        this.metallumExtra$commitNs = 0L;
    }

    @Inject(method = "blitCommandEncoder()Lio/github/kokodio/metaljvm/metal/MTLBlitCommandEncoder;", at = @At("HEAD"))
    private void metallumExtra$blitPass(final CallbackInfoReturnable<?> cir) {
        FrameProfiler.blitPassStarted();
    }

    @Inject(
            method = "renderCommandEncoder(Lio/github/kokodio/metaljvm/metal/MTLRenderPassDescriptor;)Lio/github/kokodio/metaljvm/metal/MTLRenderCommandEncoder;",
            at = @At("HEAD")
    )
    private void metallumExtra$renderPass(final CallbackInfoReturnable<?> cir) {
        FrameProfiler.renderPassStarted();
    }
}
