package com.metallumextra.mixin.shader;

import com.metallumextra.shader.sodium.SodiumHooks;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Shaders: a way for the shadow pass to reach Sodium's chunk meshes.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererShaderMixin implements SodiumHooks.WorldRenderer {
    @Shadow private RenderSectionManager renderSectionManager;

    @Override
    public RenderSectionManager metallumExtra$sections() {
        return this.renderSectionManager;
    }
}
