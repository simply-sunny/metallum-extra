package com.metallumextra.mixin.shader;

import com.metallumextra.shader.IrisWorld;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Shaders: the game checks a pass's color attachments against the pipeline's color targets before the backend sees the pipeline,
 * and a pass that draws the world has more attachments than the game's own pipelines declare; so the pack's copy is chosen here.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassPipelineMixin {
    @Shadow @Final private List<?> colorAttachments;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private RenderPipeline metallumExtra$packPipeline(final RenderPipeline pipeline) {
        RenderPipeline copy = IrisWorld.pipelineFor(pipeline);
        // A pass the pack's buffers were not attached to (not the game's image) keeps the game's pipelines.
        return copy.getColorTargetStates().length == this.colorAttachments.size() ? copy : pipeline;
    }
}
