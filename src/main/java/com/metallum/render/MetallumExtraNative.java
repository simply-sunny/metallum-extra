package com.metallum.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLOrigin;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.metal.MTLRegion;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textures of kinds the game's own graphics layer has none of (a 3D texture), made straight with Metal. The game is given a one-pixel texture to stand
 * for each, which is what the shader pack's programs are handed by name; where Metallum binds it to a draw, the real one is bound instead (see
 * {@link #resolve}). Lives in Metallum's package to reach the device and the placeholders' Metal textures.
 */
public final class MetallumExtraNative {
    /** The Metal texture of a placeholder (its address) and the texture that stands behind it. */
    private static final Map<Long, MTLTexture> BEHIND = new ConcurrentHashMap<>();

    private MetallumExtraNative() {
    }

    /** Whether the game's textures that can be drawn into can also be written by a compute program (a pack that has one is in use). */
    private static volatile boolean storageTextures;

    public static void storageTextures(final boolean on) {
        storageTextures = on;
    }

    /** Metal's usage flags for a texture the game made, with the one a compute program needs added where it is wanted. */
    public static long usage(final long usage, final int flags) {
        return storageTextures && (flags & 8) != 0 ? usage | MTLTextureUsage.ShaderWrite : usage;
    }

    /** The Metal device; null until the first frame's first pass has been made. */
    public static MTLDevice device() {
        MetalCommandEncoder encoder = MetallumExtraEncoder.encoder();
        return encoder == null ? null : encoder.commandBuffer().device();
    }

    /** What a texture of the internal format {@code format} is made of in Metal: the pixel format and the bytes of one channel (null for a format this cannot make). */
    public record Layout(MTLPixelFormat pixelFormat, int channels, int channelBytes, long one) {
    }

    /** The Metal pixel format of an OpenGL internal format name ({@code RGB16F} has no three-channel Metal format and gets four). */
    public static Layout layoutOf(final String format) {
        return switch (format) {
            case "R8" -> new Layout(MTLPixelFormat.R8Unorm, 1, 1, 0xFF);
            case "RG8" -> new Layout(MTLPixelFormat.RG8Unorm, 2, 1, 0xFF);
            case "RGB8", "RGBA8" -> new Layout(MTLPixelFormat.RGBA8Unorm, 4, 1, 0xFF);
            case "R8UI" -> new Layout(MTLPixelFormat.R8Uint, 1, 1, 1);
            case "RG8UI" -> new Layout(MTLPixelFormat.RG8Uint, 2, 1, 1);
            case "RGBA8UI" -> new Layout(MTLPixelFormat.RGBA8Uint, 4, 1, 1);
            case "R16" -> new Layout(MTLPixelFormat.R16Unorm, 1, 2, 0xFFFF);
            case "RG16" -> new Layout(MTLPixelFormat.RG16Unorm, 2, 2, 0xFFFF);
            case "RGB16", "RGBA16" -> new Layout(MTLPixelFormat.RGBA16Unorm, 4, 2, 0xFFFF);
            case "R16F" -> new Layout(MTLPixelFormat.R16Float, 1, 2, 0x3C00);
            case "RG16F" -> new Layout(MTLPixelFormat.RG16Float, 2, 2, 0x3C00);
            case "RGB16F", "RGBA16F" -> new Layout(MTLPixelFormat.RGBA16Float, 4, 2, 0x3C00);
            case "R16UI" -> new Layout(MTLPixelFormat.R16Uint, 1, 2, 1);
            case "R32F" -> new Layout(MTLPixelFormat.R32Float, 1, 4, 0x3F800000L);
            case "RG32F" -> new Layout(MTLPixelFormat.RG32Float, 2, 4, 0x3F800000L);
            case "RGB32F", "RGBA32F" -> new Layout(MTLPixelFormat.RGBA32Float, 4, 4, 0x3F800000L);
            case "R32UI" -> new Layout(MTLPixelFormat.R32Uint, 1, 4, 1);
            default -> null;
        };
    }

    /**
     * A texture of raw numbers, in the layout the file has them and made into the layout of the Metal format (a missing channel is zero, a missing fourth
     * channel one).
     *
     * @param type       {@code TEXTURE_1D}, {@code TEXTURE_2D} or {@code TEXTURE_3D}
     * @param sourceChannels the channels the data has per pixel
     * @param sourceBytes    the bytes in each channel of the data
     */
    public static MTLTexture create(final String label, final String type, final String format, final int width, final int height, final int depth,
                                    final int sourceChannels, final int sourceBytes, final byte[] data) {
        Layout layout = layoutOf(format);
        if (layout == null) throw new IllegalArgumentException("the texture format " + format + " is not supported");
        if (layout.channelBytes != sourceBytes) throw new IllegalArgumentException(format + " has " + layout.channelBytes + " bytes per channel, the file " + sourceBytes);
        long expected = (long) width * height * depth * sourceChannels * sourceBytes;
        if (data.length < expected) throw new IllegalArgumentException("the file has " + data.length + " bytes, " + width + "x" + height + "x" + depth + " needs " + expected);
        MTLDevice device = device();
        if (device == null) throw new IllegalStateException("no Metal device yet");
        MTLTextureDescriptor descriptor = MTLTextureDescriptor.alloc().init();
        descriptor.setTextureType(switch (type) {
            case "TEXTURE_1D" -> MTLTextureType.Type1D;
            case "TEXTURE_3D" -> MTLTextureType.Type3D;
            default -> MTLTextureType.Type2D;
        });
        descriptor.setPixelFormat(layout.pixelFormat);
        descriptor.setWidth(width);
        descriptor.setHeight(height);
        descriptor.setDepth(depth);
        descriptor.setMipmapLevelCount(1);
        descriptor.setStorageMode(MTLStorageMode.Shared);
        descriptor.setUsage(MTLTextureUsage.ShaderRead | MTLTextureUsage.ShaderWrite);
        MTLTexture texture = device.newTexture(descriptor);
        descriptor.release();
        if (texture == null || texture.handle() == 0L) throw new IllegalStateException("Metal would not make the texture " + label);
        int rowBytes = width * layout.channels * layout.channelBytes;
        ByteBuffer rows = ByteBuffer.allocateDirect(rowBytes * height * depth).order(ByteOrder.LITTLE_ENDIAN);
        int sourcePixel = sourceChannels * sourceBytes;
        for (int pixel = 0; pixel < width * height * depth; pixel++) {
            for (int channel = 0; channel < layout.channels; channel++) {
                if (channel < sourceChannels) {
                    rows.put(data, pixel * sourcePixel + channel * sourceBytes, sourceBytes);
                } else {
                    long fill = channel == 3 ? layout.one : 0L;
                    for (int b = 0; b < layout.channelBytes; b++) rows.put((byte) (fill >> (8 * b)));
                }
            }
        }
        rows.flip();
        MemorySegment bytes = MemorySegment.ofBuffer(rows);
        MTLRegion region = new MTLRegion(new MTLOrigin(0, 0, 0), new MTLSize(width, height, depth));
        texture.replaceRegion(region, 0, 0, bytes, rowBytes, (long) rowBytes * height);
        return texture;
    }

    /** From now on, wherever the placeholder's texture is bound to a draw, {@code real} is bound. */
    public static void stand(final GpuTextureView placeholder, final MTLTexture real) {
        BEHIND.put(((MetalGpuTextureView) placeholder).metalTexture().handle(), real);
    }

    /** The placeholder is gone (or its texture is): the real one is let go. */
    public static void forget(final GpuTextureView placeholder) {
        MTLTexture real = BEHIND.remove(((MetalGpuTextureView) placeholder).metalTexture().handle());
        if (real != null) real.release();
    }

    /** The texture to bind in place of {@code bound}: the real texture a placeholder stands for, or {@code bound} itself (nearly always). */
    public static MTLTexture resolve(final MTLTexture bound) {
        if (BEHIND.isEmpty()) return bound;
        MTLTexture real = BEHIND.get(bound.handle());
        return real == null ? bound : real;
    }
}
