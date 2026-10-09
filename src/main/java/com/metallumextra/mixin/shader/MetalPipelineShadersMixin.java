package com.metallumextra.mixin.shader;

import com.metallumextra.shader.IrisWorld;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Shaders: the shaders a pipeline is compiled from are looked up by id, and one id serves many of the game's pipelines (entities, their
 * translucent kin, nametags ...). A standard pack has a program for each kind of object, so the id of a pipeline's shaders is where
 * the pack's program for that pipeline is put in; see {@link IrisWorld}.
 */
@Mixin(targets = "com.metallum.render.MetalCrossShaderCompiler", remap = false)
public abstract class MetalPipelineShadersMixin {
    @Redirect(method = "compile", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;getVertexShader()Lnet/minecraft/resources/Identifier;"))
    private static Identifier metallumExtra$vertexShader(final RenderPipeline pipeline) {
        return IrisWorld.shaderId(pipeline, pipeline.getVertexShader());
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;getFragmentShader()Lnet/minecraft/resources/Identifier;"))
    private static Identifier metallumExtra$fragmentShader(final RenderPipeline pipeline) {
        return IrisWorld.shaderId(pipeline, pipeline.getFragmentShader());
    }
}
