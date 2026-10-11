package com.metallumextra.fx;

import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.metalfx.MTLFXSpatialScalerDescriptor;
import io.github.kokodio.metaljvm.metalfx.MTLFXTemporalDenoisedScalerDescriptor;
import io.github.kokodio.metaljvm.metalfx.MTLFXTemporalScalerDescriptor;

/** Apple's MetalFX upscalers. */
public final class MetalFx {
    private MetalFx() {
    }

    /** What MetalFX says about this machine, one line per fact. */
    public static String probe() {
        StringBuilder out = new StringBuilder();
        MTLDevice device = Metal.MTLCreateSystemDefaultDevice();
        out.append("device ").append(device == null ? "none" : device.name()).append('\n');
        if (device == null) return out.toString();
        out.append("MTLFXSpatialScalerDescriptor: supported on this GPU = ").append(MTLFXSpatialScalerDescriptor.supportsDevice(device)).append('\n');
        out.append("MTLFXTemporalScalerDescriptor: supported on this GPU = ").append(MTLFXTemporalScalerDescriptor.supportsDevice(device)).append('\n');
        out.append("MTLFXTemporalDenoisedScalerDescriptor: supported on this GPU = ").append(MTLFXTemporalDenoisedScalerDescriptor.supportsDevice(device)).append('\n');
        return out.toString();
    }
}
