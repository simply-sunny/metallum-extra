package com.metallumextra.mixin.shader;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a second dispatcher for the held item is made of (see {@code GameRendererShaderMixin#metallumExtra$drawHandNow}). */
@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessor {
    @Accessor("modelManager")
    net.minecraft.client.resources.model.ModelManager metallumExtra$modelManager();

    @Accessor("atlasManager")
    net.minecraft.client.resources.model.sprite.AtlasManager metallumExtra$atlasManager();

    @Accessor("font")
    Font metallumExtra$font();

    @Accessor("gameRenderState")
    GameRenderState metallumExtra$gameRenderState();
}
