package com.metallumextra.mixin;

import com.metallum.render.MetallumExtraBridge;
import com.metallumextra.SamplerSlots;
import com.metallumextra.shader.ShaderBindings;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the pipeline's sampler renumbering, if it needed one (see {@link SamplerSlots}), and which of the shader
 * pipeline's own uniform block and textures its shaders read (see {@link ShaderBindings}).
 */
@Mixin(targets = "com.metallum.render.MetalCompiledRenderPipeline", remap = false)
public abstract class MetalCompiledRenderPipelineMixin implements SamplerSlots.Pipeline, ShaderBindings.Pipeline {
    @Unique
    private byte @Nullable [] metallumExtra$samplerSlots;

    @Unique
    private long metallumExtra$shaderBindings;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallumExtra$adoptResources(final @org.spongepowered.asm.mixin.injection.Coerce Object device, final com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo info,
                                              final @org.spongepowered.asm.mixin.injection.Coerce Object compiled, final CallbackInfo ci) {
        this.metallumExtra$samplerSlots = MetallumExtraBridge.samplerSlots(this, info.name());
        this.metallumExtra$shaderBindings = MetallumExtraBridge.resourceMask(this, ShaderBindings.NAMES);
    }

    @Override
    public long metallumExtra$shaderBindings() {
        return this.metallumExtra$shaderBindings;
    }

    @Override
    public byte @Nullable [] metallumExtra$samplerSlots() {
        return this.metallumExtra$samplerSlots;
    }
}
