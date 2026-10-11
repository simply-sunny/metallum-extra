package com.metallumextra.mixin.voxy;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the sprite's per-mip image array so {@link SoftwareModelTextureBakeryMixin} can blit the
 * mip-0 data (always present, the untouched original image) back into the int[] Voxy's software
 * rasterizer samples. Read-only accessor, no behaviour change.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("byMipLevel")
    NativeImage[] metallumExtra$byMipLevel();
}
