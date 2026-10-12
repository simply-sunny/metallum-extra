package com.metallumextra.mixin.shader;

import com.metallumextra.shader.IrisPipeline;
import com.metallumextra.shader.IrisWorld;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shaders: an entity's model is staged under the entity's id from the pack's {@code entity.properties}, so the draws of the entities with an id are kept
 * apart from each other and drawn with the pack's program for that id (see {@link IrisWorld#entityPipelineFor}).
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererEntityMixin {
    @Inject(method = "prepareModel", at = @At("HEAD"))
    private void metallumExtra$entityBegins(final ModelFeatureRenderer.Submit<?> submit, final CallbackInfo ci) {
        IrisWorld.preparingEntity = IrisPipeline.entityIdOf(submit.state());
    }

    @Inject(method = "prepareModel", at = @At("RETURN"))
    private void metallumExtra$entityEnds(final ModelFeatureRenderer.Submit<?> submit, final CallbackInfo ci) {
        IrisWorld.preparingEntity = 0;
    }
}
