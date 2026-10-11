package com.metallum.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLEvent;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metalfx.MTLFXSpatialScaler;
import io.github.kokodio.metaljvm.metalfx.MTLFXSpatialScalerColorProcessingMode;
import io.github.kokodio.metaljvm.metalfx.MTLFXSpatialScalerDescriptor;

/**
 * MetalFX's spatial scaler, put into the frame being recorded. Lives in Metallum's package to reach the command encoder and its command buffer.
 * <p>
 * The scaler is encoded into the same command buffer as the frame's other work, between two render passes: whatever encoder is open is ended first
 * (Metallum opens the next one when the next pass starts), and what the scaler reads and writes has its deferred clears drawn before.
 */
public final class MetallumExtraUpscale {
    /** Not known yet: no frame has been recorded, so the GPU cannot be asked. */
    public static final String PENDING = "pending";

    private static MetalCommandEncoder encoder;
    private static MTLFXSpatialScaler scaler;
    /** Metallum's textures are not tracked by Metal, so it orders its passes with one fence; MetalFX's own passes know nothing of that and are ordered with this event. */
    private static MTLEvent event;
    private static long eventValue;
    private static long builtInW, builtInH, builtOutW, builtOutH;
    private static MTLPixelFormat builtFormat;

    private MetallumExtraUpscale() {
    }

    /** The encoder frames are recorded with, once a pass has been made; null before. */
    static MetalCommandEncoder encoder() {
        return encoder;
    }

    /** Remembers the encoder frames are recorded with. Called as passes are created. */
    public static void capture(final Object commandEncoder) {
        encoder = (MetalCommandEncoder) commandEncoder;
    }

    /** Whether this machine's GPU has MetalFX's spatial scaler; null if it does, else why not. */
    public static String unsupportedReason() {
        try {
            MetalCommandEncoder current = encoder;
            if (current == null) return PENDING;
            MTLDevice device = current.commandBuffer().device();
            return MTLFXSpatialScalerDescriptor.supportsDevice(device) ? null : "this GPU does not support MetalFX's spatial scaler";
        } catch (RuntimeException | Error e) {
            return "this macOS has no MetalFX (" + e + ")";
        }
    }

    /**
     * Scales {@code in} (a picture of inW x inH) to {@code out} (outW x outH) in the frame being recorded. Returns null when done, or the reason it could not be.
     */
    public static String encodeSpatial(final GpuTextureView in, final GpuTextureView out, final int inW, final int inH, final int outW, final int outH) {
        MetalCommandEncoder current = encoder;
        if (current == null) return "no frame is being recorded";
        try {
            // Whatever the game drew earlier into these two must be in the command buffer before the scaler reads or writes them.
            current.flushPendingClear((MetalGpuTexture) in.texture());
            current.flushPendingClear((MetalGpuTexture) out.texture());
            current.endEncoder();
            MTLCommandBuffer commandBuffer = current.commandBuffer();
            MTLTexture inTexture = ((MetalGpuTextureView) in).metalTexture();
            MTLTexture outTexture = ((MetalGpuTextureView) out).metalTexture();
            MTLPixelFormat format = inTexture.pixelFormat();
            if (scaler == null || builtInW != inW || builtInH != inH || builtOutW != outW || builtOutH != outH || builtFormat != format) {
                release();
                MTLDevice device = commandBuffer.device();
                MTLFXSpatialScalerDescriptor descriptor = MTLFXSpatialScalerDescriptor.alloc().init();
                descriptor.setInputWidth(inW);
                descriptor.setInputHeight(inH);
                descriptor.setOutputWidth(outW);
                descriptor.setOutputHeight(outH);
                descriptor.setColorTextureFormat(format);
                descriptor.setOutputTextureFormat(outTexture.pixelFormat());
                descriptor.setColorProcessingMode(MTLFXSpatialScalerColorProcessingMode.Perceptual);
                MTLFXSpatialScaler made = descriptor.newSpatialScaler(device);
                descriptor.release();
                if (made == null || made.handle() == 0L) return "MetalFX would not make a scaler for " + inW + "x" + inH + " to " + outW + "x" + outH;
                scaler = made;
                builtInW = inW;
                builtInH = inH;
                builtOutW = outW;
                builtOutH = outH;
                builtFormat = format;
            }
            if (event == null) event = commandBuffer.device().newEvent();
            // Everything encoded so far is finished before the scaler starts ...
            commandBuffer.encodeSignalEvent(event, ++eventValue);
            commandBuffer.encodeWaitForEvent(event, eventValue);
            scaler.setColorTexture(inTexture);
            scaler.setOutputTexture(outTexture);
            scaler.setInputContentWidth(inW);
            scaler.setInputContentHeight(inH);
            scaler.encodeToCommandBuffer(commandBuffer);
            // ... and the scaler is finished before anything encoded after it starts.
            commandBuffer.encodeSignalEvent(event, ++eventValue);
            commandBuffer.encodeWaitForEvent(event, eventValue);
            ((MetalGpuTexture) out.texture()).markContentsDirty();
            return null;
        } catch (Throwable e) {
            return e.toString();
        }
    }

    /** The colour this texture is waiting to be cleared to (the game clears the window's image at the start of the frame, with the fog's colour), or null. */
    public static org.joml.Vector4fc pendingClear(final com.mojang.renderpearl.api.textures.GpuTexture texture) {
        MetalCommandEncoder current = encoder;
        return current == null ? null : ((com.metallumextra.fx.PendingClearSource) (Object) current).metallumExtra$pendingClear(texture);
    }

    /** Draws the clears these textures are waiting for now. */
    public static void flushClears(final com.mojang.renderpearl.api.textures.GpuTexture... textures) {
        MetalCommandEncoder current = encoder;
        if (current == null) return;
        for (com.mojang.renderpearl.api.textures.GpuTexture texture : textures) current.flushPendingClear((MetalGpuTexture) texture);
    }

    /** Lets the scaler go (the window changed size, or the upscaling was switched off). */
    public static void release() {
        if (scaler != null) scaler.release();
        scaler = null;
    }
}
