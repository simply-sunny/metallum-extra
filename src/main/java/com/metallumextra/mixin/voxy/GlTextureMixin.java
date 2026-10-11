package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.gl.VoxyGlRegistry;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives Voxy's textures fake ids with dimensions-only book-keeping in {@link VoxyGlRegistry}. The
 * only texture our CPU pipeline actually produces data for is the baked model atlas, and those
 * per-cell writes are captured into {@code ModelAtlasMirror} by the {@code ModelFactory} upload
 * mixin, so no pixel storage is kept here.
 */
@Mixin(value = GlTexture.class, remap = false)
public abstract class GlTextureMixin {
    /** {@code glCreateTextures}: mint a fake texture id (both public and private constructors). */
    @Redirect(method = {"<init>(I)V", "<init>(IZ)V"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glCreateTextures(I)I"))
    private static int metallumExtra$fakeTextureId(final int type) {
        return VoxyGlRegistry.allocTextureId();
    }

    /** {@code glGenTextures} (legacy path in the private constructor): same fake-id minting. */
    @Redirect(method = "<init>(IZ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glGenTextures()I"))
    private static int metallumExtra$fakeGenTextureId() {
        return VoxyGlRegistry.allocTextureId();
    }

    /** {@code glTextureStorage2D}: record width/height/levels; no pixels are needed on the CPU side. */
    @Redirect(method = "store", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glTextureStorage2D(IIIII)V"))
    private static void metallumExtra$allocateStorage(final int texture, final int levels, final int format,
                                                      final int width, final int height) {
        VoxyGlRegistry.allocTexture(texture, width, height, levels);
    }

    /** {@code glDeleteTextures}: drop the book-keeping entry. */
    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glDeleteTextures(I)V"))
    private static void metallumExtra$deleteTexture(final int texture) {
        VoxyGlRegistry.free(texture);
    }
}
