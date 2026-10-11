package com.metallumextra.mixin.shader;

import com.metallumextra.shader.IrisPipeline;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Shaders: while the world is drawn, a pass into the game's image gets the other buffers a standard pack's programs write as color targets. */
@Mixin(FrontendCommandEncoder.class)
public abstract class FrontendCommandEncoderShaderMixin {
    private static final boolean LOG_PASSES = Boolean.getBoolean("metallumextra.logPasses");

    @ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;", at = @At("HEAD"), argsOnly = true)
    private RenderPassDescriptor metallumExtra$worldTargets(final RenderPassDescriptor descriptor) {
        RenderPassDescriptor result = IrisPipeline.attachWorldTargets(descriptor);
        if (LOG_PASSES && com.metallumextra.shader.Shaders.phase() != com.metallumextra.shader.Shaders.PHASE_NONE) {
            com.metallumextra.MetallumExtra.LOGGER.info("PASS phase={} {} attachments={}->{}", com.metallumextra.shader.Shaders.phase(), descriptor.label(), descriptor.colorAttachments().size(), result.colorAttachments().size());
        }
        return result;
    }
}
