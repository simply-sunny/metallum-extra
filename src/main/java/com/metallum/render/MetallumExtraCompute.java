package com.metallum.render;

import com.metallumextra.shader.ComputeBinding;
import com.metallumextra.shader.ComputeCompiler;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLComputeCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLComputePipelineState;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLEvent;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLSize;

import java.util.List;

/**
 * Compute programs, run inside the frame being recorded. Lives in Metallum's package to reach the command encoder and the Metal objects behind the game's
 * textures, samplers and buffers.
 * <p>
 * A program runs between two render passes: whatever encoder is open is ended first (Metallum opens the next one when the next pass starts), and what the
 * program reads and writes has its deferred clears drawn before. Metallum's textures are not tracked by Metal, so what ran before the program is waited for
 * with an event, and the program is waited for the same way (as the upscaler is).
 */
public final class MetallumExtraCompute {
    private static MTLEvent event;
    private static long eventValue;

    private MetallumExtraCompute() {
    }

    /** A kernel made ready to run. */
    public record Kernel(MTLComputePipelineState state, String label) {
    }

    /** Makes the Metal source into a kernel; null if Metal would not take it (Metallum has logged why). */
    public static Kernel create(final String label, final String metal, final String entry) {
        MetalCommandEncoder encoder = MetallumExtraUpscale.encoder();
        if (encoder == null) throw new IllegalStateException("no frame has been recorded yet");
        MTLDevice device = encoder.commandBuffer().device();
        MTLLibrary library = MetalUtilities.newLibrary(device, metal);
        if (library == null) return null;
        try {
            MTLFunction function = library.newFunctionWithName(entry);
            if (function == null) return null;
            function.setLabel(label);
            MTLComputePipelineState state = device.newComputePipelineStateWithFunction(function);
            function.release();
            return state == null ? null : new Kernel(state, label);
        } finally {
            library.release();
        }
    }

    /** Runs the kernel over {@code groups} work groups of {@code local} threads. Returns null when it has been recorded, or why it could not be. */
    public static String dispatch(final Kernel kernel, final List<ComputeBinding> bindings, final int[] groups, final int[] local) {
        MetalCommandEncoder current = MetallumExtraUpscale.encoder();
        if (current == null) return "no frame is being recorded";
        try {
            for (ComputeBinding binding : bindings) {
                if (binding.texture() != null) current.flushPendingClear((MetalGpuTexture) binding.texture().texture());
            }
            current.endEncoder();
            MTLCommandBuffer commandBuffer = current.commandBuffer();
            if (event == null) event = commandBuffer.device().newEvent();
            // Everything encoded so far is finished before the kernel starts ...
            commandBuffer.encodeSignalEvent(event, ++eventValue);
            commandBuffer.encodeWaitForEvent(event, eventValue);
            MTLComputeCommandEncoder encoder = commandBuffer.computeCommandEncoder();
            encoder.setLabel(kernel.label());
            encoder.setComputePipelineState(kernel.state());
            for (ComputeBinding binding : bindings) {
                ComputeCompiler.Resource resource = binding.resource();
                switch (resource.kind()) {
                    case BLOCK, BUFFER -> {
                        MTLBuffer buffer = ((MetalGpuBuffer) binding.buffer().buffer()).metalBuffer();
                        encoder.setBuffer(buffer, binding.buffer().offset(), resource.slot());
                    }
                    case TEXTURE -> {
                        encoder.setTexture(MetallumExtraNative.resolve(((MetalGpuTextureView) binding.texture()).metalTexture()), resource.slot());
                        if (resource.sampler() >= 0) encoder.setSamplerState(((MetalGpuSampler) binding.sampler()).metalSampler(), resource.sampler());
                    }
                    case IMAGE -> encoder.setTexture(MetallumExtraNative.resolve(((MetalGpuTextureView) binding.texture()).metalTexture()), resource.slot());
                }
            }
            encoder.dispatchThreadgroups(new MTLSize(groups[0], groups[1], groups[2]), new MTLSize(local[0], local[1], local[2]));
            encoder.endEncoding();
            // ... and the kernel is finished before anything encoded after it starts.
            commandBuffer.encodeSignalEvent(event, ++eventValue);
            commandBuffer.encodeWaitForEvent(event, eventValue);
            for (ComputeBinding binding : bindings) {
                if (binding.writes() && binding.texture() != null) ((MetalGpuTexture) binding.texture().texture()).markContentsDirty();
            }
            return null;
        } catch (Throwable e) {
            return e.toString();
        }
    }
}
