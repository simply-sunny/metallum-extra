package com.metallumextra.mixin.voxy;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.model.bakery.SoftwareRasterizer;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy's software rasterizer samples the vanilla block atlas through a plain int[] it read back
 * from the GPU with {@code glGetTextureImage} — and it obtained the GL name by casting MC's texture
 * to the OpenGL backend's {@code GlTexture}, which throws ClassCastException under Metal. The whole
 * method body is replaced here: the same RGBA int[] is reconstructed on the CPU from the atlas's
 * {@code SpriteContents} (each sprite's mip-0 {@link NativeImage} blitted at its atlas position via
 * the accessor mixins), and handed to the rasterizer exactly as the GL readback would have been.
 *
 * <p>Byte order: {@code glGetTextureImage(..., GL_RGBA, GL_UNSIGNED_BYTE, int[])} produced raw
 * R,G,B,A byte-quad memory, i.e. ints of 0xAABBGGRR on this little-endian target. MC's
 * {@link NativeImage} (Format.RGBA) stores precisely R,G,B,A bytes per pixel in memory (its
 * {@code getPixel} reads that layout as an "ABGR" int), so a straight row-wise memory copy
 * reproduces the GL readback bit-for-bit with no swizzle. Regions not covered by any sprite stay 0
 * (the GL readback would have contained whatever the atlas held there, i.e. only sprite texels are
 * ever sampled).</p>
 */
@Mixin(value = SoftwareModelTextureBakery.class, remap = false)
public abstract class SoftwareModelTextureBakeryMixin {
    @Shadow
    private SoftwareRasterizer rasterizer;

    @Inject(method = "setupTexture", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$rebuildAtlasOnCpu(final CallbackInfo ci) {
        final var textureManager = Minecraft.getInstance().getTextureManager();
        final Identifier blocksAtlas = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");

        // Width/height exactly as the original got them: from the GPU texture's mip 0.
        final var gpuTexture = textureManager.getTexture(blocksAtlas).getTexture();
        final int width = gpuTexture.getWidth(0);
        final int height = gpuTexture.getHeight(0);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Block atlas has invalid size " + width + "x" + height);
        }

        final int[] texture;
        // A scratch RGBA NativeImage doubles as raw pixel memory (same R,G,B,A byte layout the GL
        // readback produced); getPixelsABGR then bulk-reads that memory as the int[] as-is.
        try (NativeImage atlas = new NativeImage(width, height, true)) {
            final long dstBase = atlas.getPointer();

            for (final TextureAtlasSprite sprite : ((TextureAtlasAccessor) textureManager.getTexture(blocksAtlas)).metallumExtra$sprites()) {
                final NativeImage image = ((SpriteContentsAccessor) sprite.contents()).metallumExtra$byMipLevel()[0];
                if (image == null || image.isClosed()) {
                    continue;
                }
                final int spriteWidth = image.getWidth();
                final int spriteHeight = image.getHeight();
                final int atX = sprite.getX();
                final int atY = sprite.getY();
                if (spriteWidth <= 0 || spriteHeight <= 0 || atX < 0 || atY < 0 || atX + spriteWidth > width || atY + spriteHeight > height) {
                    continue;//Defensive: never write outside the reconstructed atlas
                }

                final long srcBase = image.getPointer();
                for (int row = 0; row < spriteHeight; row++) {
                    UnsafeUtil.memcpy(srcBase + (long) row * spriteWidth * 4L,
                            dstBase + ((long) (atY + row) * width + atX) * 4L,
                            (long) spriteWidth * 4L);
                }
            }

            texture = atlas.getPixelsABGR();
        }

        this.rasterizer.setSamplerTexture(texture, width, height);
        ci.cancel();
    }
}
