package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.gl.ModelAtlasMirror;
import me.cortex.voxy.client.core.model.ModelFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The end of Voxy's model-bake pipeline on a GL driver is an atlas sub-image upload: every baked
 * model writes one 48x32 RGBA cell ({@code MODEL_TEXTURE_SIZE*3 x *2}) at
 * {@code x=(modelId&0xFF)*48, y=((modelId>>8)&0xFF)*32} of the model atlas texture, plus optional
 * mip levels. Under Metal the texture is mirrored on the CPU instead: the level-0 write is
 * redirected into {@link ModelAtlasMirror#writeCell}, with the model id recovered from the GL
 * coordinates, and mip levels are dropped (the later Metal stage samples LOD 0 only). The staged
 * model/colour data in the same method flows through the already-shimmed {@code UploadStream}.
 */
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory$ModelBakeResultUpload", remap = false)
public abstract class ModelFactoryUploadMixin {
    /** {@code nglTextureSubImage2D}: capture the cell into the CPU atlas mirror, ignore mips. */
    @Redirect(method = "upload(Lme/cortex/voxy/client/core/gl/GlBuffer;Lme/cortex/voxy/client/core/gl/GlBuffer;Lme/cortex/voxy/client/core/gl/GlTexture;)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/ARBDirectStateAccess;nglTextureSubImage2D(IIIIIIIIJ)V"))
    private static void metallumExtra$atlasUpload(final int texture, final int level, final int x, final int y,
                                                  final int width, final int height, final int format,
                                                  final int type, final long pixels) {
        if (level != 0) {
            return; // The Metal mirror is mip-less; the later stage samples LOD 0.
        }
        // x = (modelId & 0xFF) * 48 and y = ((modelId >> 8) & 0xFF) * 32 (see ModelFactory.upload).
        final int modelId = (x / ModelAtlasMirror.CELL_W) | ((y / ModelAtlasMirror.CELL_H) << 8);
        ModelAtlasMirror.writeCell(modelId, pixels);
    }
}
