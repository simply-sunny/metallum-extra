package com.metallumextra.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Metal supports independent blending per color attachment (each attachment has its own {@code MTLRenderPipelineColorAttachmentDescriptor}).
 * RenderPearl's builder contains a check enforcing that all color targets have identical blend functions, which prevents shader packs
 * (such as iterationT) from specifying independent blend functions for different attachments (e.g. translucent water vs normal/specular).
 */
@Mixin(RenderPipeline.Builder.class)
public abstract class RenderPipelineBuilderMixin {
    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Ljava/util/Optional;equals(Ljava/lang/Object;)Z"))
    private boolean metallumExtra$allowDifferentBlendFunctions(final Optional<?> instance, final Object other, final Operation<Boolean> original) {
        return true;
    }
}
