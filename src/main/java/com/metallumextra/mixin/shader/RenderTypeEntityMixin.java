package com.metallumextra.mixin.shader;

import com.metallumextra.shader.IrisWorld;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shaders: while an entity with an id is staged, its draws are not merged with others (so each keeps its id), and its prepared render type draws with the
 * pack's pipeline for the id (see {@link IrisWorld#entityPipelineFor}).
 */
@Mixin(RenderType.class)
public abstract class RenderTypeEntityMixin {
    @Inject(method = "prepare", at = @At("RETURN"), cancellable = true)
    private void metallumExtra$entityPipeline(final CallbackInfoReturnable<PreparedRenderType> cir) {
        int entity = IrisWorld.preparingEntity;
        if (entity == 0) return;
        PreparedRenderType prepared = cir.getReturnValue();
        RenderPipeline variant = IrisWorld.entityPipelineFor(prepared.pipeline(), entity);
        if (variant != prepared.pipeline()) {
            cir.setReturnValue(new PreparedRenderType(prepared.name(), variant, prepared.oitPipelineSet(), prepared.dynamicTransforms(), prepared.scissorState(),
                    prepared.textures()));
        }
    }

    @Inject(method = "canConsolidateConsecutiveGeometry", at = @At("RETURN"), cancellable = true)
    private void metallumExtra$ownDraws(final CallbackInfoReturnable<Boolean> cir) {
        if (IrisWorld.preparingEntity != 0) cir.setReturnValue(false);
    }
}
