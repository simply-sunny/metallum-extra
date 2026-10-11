package com.metallumextra.shader;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * What goes into one slot of a compute program: a block of uniforms, or a texture with its sampler (the texture is only read), or an image (read and
 * written, so the pass that wrote it before is waited for and what it writes is known to be new).
 */
public record ComputeBinding(ComputeCompiler.Resource resource, @Nullable GpuBufferSlice buffer, @Nullable GpuTextureView texture, @Nullable GpuSampler sampler, boolean writes) {
}
