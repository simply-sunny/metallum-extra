package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * A picture of raw numbers that shaders.properties asks for: {@code texture.deferred.depthtex0 = image/lut.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT}
 * (the file, what kind of texture it is, how it is stored, its size and how the file's numbers are laid out). A plain {@code .png} has no such description.
 *
 * @param path        the file inside the pack
 * @param target      {@code TEXTURE_1D}, {@code TEXTURE_2D} or {@code TEXTURE_3D}
 * @param format      the internal format ({@code R8}, {@code RGBA16F}, ...)
 * @param dims        width, then height and depth as far as the target has them
 * @param pixelFormat the channels in the file ({@code RED}, {@code RG}, {@code RGB}, {@code RGBA}, ...)
 * @param pixelType   how a number in the file is stored ({@code UNSIGNED_BYTE}, {@code HALF_FLOAT}, ...)
 */
public record TextureSpec(String path, String target, String format, int[] dims, String pixelFormat, String pixelType) {
    /** The description in a {@code texture.*} value, or null when the value is only a file name. */
    public static @Nullable TextureSpec parse(final String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2) return null;
        String target = parts[1].toUpperCase(Locale.ROOT);
        int count = switch (target) {
            case "TEXTURE_1D" -> 1;
            case "TEXTURE_2D" -> 2;
            case "TEXTURE_3D" -> 3;
            default -> -1;
        };
        if (count < 0 || parts.length != 2 + 1 + count + 2) return null;
        try {
            int[] dims = new int[count];
            for (int i = 0; i < count; i++) dims[i] = Integer.parseInt(parts[3 + i]);
            return new TextureSpec(parts[0], target, parts[2].toUpperCase(Locale.ROOT), dims, parts[3 + count].toUpperCase(Locale.ROOT), parts[4 + count].toUpperCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The file name of a {@code texture.*} value, with or without a description. */
    public static String pathOf(final String value) {
        return value.trim().split("\\s+")[0];
    }

    public int width() {
        return dims[0];
    }

    public int height() {
        return dims.length > 1 ? dims[1] : 1;
    }

    public int depth() {
        return dims.length > 2 ? dims[2] : 1;
    }

    /** How many channels the file holds per pixel. */
    public int sourceChannels() {
        return switch (pixelFormat) {
            case "RED", "RED_INTEGER", "DEPTH_COMPONENT" -> 1;
            case "RG", "RG_INTEGER" -> 2;
            case "RGB", "BGR", "RGB_INTEGER", "BGR_INTEGER" -> 3;
            case "RGBA", "BGRA", "RGBA_INTEGER", "BGRA_INTEGER" -> 4;
            default -> throw new IllegalArgumentException("pixel format " + pixelFormat);
        };
    }

    /** The size in bytes of one number in the file. */
    public int sourceBytes() {
        return switch (pixelType) {
            case "BYTE", "UNSIGNED_BYTE" -> 1;
            case "SHORT", "UNSIGNED_SHORT", "HALF_FLOAT" -> 2;
            case "INT", "UNSIGNED_INT", "FLOAT" -> 4;
            default -> throw new IllegalArgumentException("pixel type " + pixelType);
        };
    }
}
