package com.metallumextra.mixin.voxy;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The CPU rebuild of Voxy's atlas readback (see {@link SoftwareModelTextureBakeryMixin}) needs the
 * atlas's stitched sprite list, which MC keeps private. Read-only accessor, no behaviour change.
 */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("sprites")
    java.util.List<TextureAtlasSprite> metallumExtra$sprites();
}
