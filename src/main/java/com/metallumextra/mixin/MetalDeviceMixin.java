package com.metallumextra.mixin;

import com.metallum.render.MetallumExtraBridge;
import com.metallumextra.FrameProfiler;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Profiler: how many new buffers with initial data Metallum writes straight into CPU-visible memory, and how many it has to copy with a GPU pass. */
@Mixin(targets = "com.metallum.render.MetalDevice", remap = false)
public abstract class MetalDeviceMixin {
    @Inject(method = "createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;", at = @At("RETURN"))
    private void metallumExtra$bufferCreatedWithData(final Supplier<String> label, final int usage, final ByteBuffer data, final CallbackInfoReturnable<GpuBuffer> cir) {
        FrameProfiler.bufferCreatedWithData(MetallumExtraBridge.writtenDirectly(cir.getReturnValue()));
    }
}
