package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.metallumextra.shader.IrisPipeline;
import com.metallumextra.shader.Shaders;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

/** Shaders: where the game's own passes give the shader pipeline room to draw. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererShaderMixin {
    /**
     * The shadow map is drawn after the game has prepared the level's chunks and before it runs any of the
     * level's own passes, which is the one point where the chunk meshes are current and no render pass is open.
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void metallumExtra$beforeWorldPasses(final CallbackInfo ci, @Local final FeatureRenderDispatcher.PreparedFrame featureFrame) {
        Shaders.beforeWorldPasses(featureFrame);
    }

    /**
     * The game draws the solid world and then everything translucent into one pass. The pack's deferred programs and the copy of the world
     * that water reads (what has been drawn so far is what it reflects and refracts) need a gap between the two, so the pass is ended there
     * and the translucent things are drawn into a new one over the same images.
     */
    @WrapOperation(method = "lambda$addMainPass$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void metallumExtra$beforeTranslucents(final LevelRenderer self, final ChunkSectionsToRender chunkSections, final FeatureRenderDispatcher.PreparedFrame featureFrame,
                                                  final RenderPass renderPass, final Operation<Void> original) {
        if (!Shaders.drawsBetweenSolidAndTranslucent()) {
            original.call(self, chunkSections, featureFrame, renderPass);
            return;
        }
        // Translucent entities the pack lights in its deferred programs are drawn with the solid world, as Iris does, and not again after.
        metallumExtra$translucentEntitiesDrawn = IrisPipeline.translucentEntitiesDeferred();
        if (metallumExtra$translucentEntitiesDrawn) featureFrame.executeTranslucent(renderPass);
        // The pass is closed here; the game closes it again when it leaves its block, which does nothing.
        renderPass.close();
        Shaders.beforeTranslucentTerrain();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (RenderPass translucent = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Main (translucent)",
                main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(translucent);
            original.call(self, chunkSections, featureFrame, translucent);
        } finally {
            metallumExtra$translucentEntitiesDrawn = false;
        }
    }

    @Unique
    private static boolean metallumExtra$translucentEntitiesDrawn;

    @WrapOperation(method = "executeClassicTransparency",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
    private void metallumExtra$translucentEntitiesOnce(final FeatureRenderDispatcher.PreparedFrame featureFrame, final RenderPass renderPass, final Operation<Void> original) {
        if (!metallumExtra$translucentEntitiesDrawn) original.call(featureFrame, renderPass);
    }
}
