package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.ShaderPack;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pictures a shader pack supplies ({@code texture.noise=lib/textures/noise.png} in {@code shaders.properties}), uploaded once and kept until the
 * pack changes. A picture beside it named {@code <file>.png.mcmeta} may say {@code "blur"} (linear filtering, the default is nearest) and
 * {@code "clamp"} (clamp to the edge, the default is repeat), as OptiFine and Iris read them.
 */
public final class PackTextures {
    private record Loaded(DynamicTexture texture, boolean blur, boolean clamp) {
    }

    private static final Map<String, Loaded> LOADED = new HashMap<>();
    private static @Nullable ShaderPack owner;
    private static final Pattern BLUR = Pattern.compile("\"blur\"\\s*:\\s*(true|false)");
    private static final Pattern CLAMP = Pattern.compile("\"clamp\"\\s*:\\s*(true|false)");

    private PackTextures() {
    }

    private static synchronized @Nullable Loaded get(final ShaderPack pack, final String path) {
        if (owner != pack) {
            release();
            owner = pack;
        }
        Loaded loaded = LOADED.get(path);
        if (loaded != null) return loaded;
        if (path.startsWith(BUILTIN)) return builtin(path);
        byte[] bytes = pack.bytes(path);
        if (bytes == null) return null;
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes))) {
            // The texture keeps its own copy of the pixels.
            NativeImage copy = new NativeImage(image.getWidth(), image.getHeight(), false);
            copy.copyFrom(image);
            DynamicTexture texture = new DynamicTexture(() -> "Metallum Extra pack texture " + path, copy);
            String meta = pack.read(path + ".mcmeta");
            boolean blur = meta != null && flag(BLUR, meta);
            boolean clamp = meta != null && flag(CLAMP, meta);
            loaded = new Loaded(texture, blur, clamp);
            LOADED.put(path, loaded);
            return loaded;
        } catch (IOException | RuntimeException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Shader pack {}: could not read the picture {}", pack.name(), path, e);
            return null;
        }
    }

    /** Prefix of the names of pictures the renderer makes itself (the pack does not supply them): {@code mx:flat_normals}, {@code mx:no_specular}. */
    public static final String BUILTIN = "mx:";

    /**
     * What a pack that reads the resource pack's normal and specular maps is given when the resource pack has none: a flat normal map (pointing straight out)
     * and a specular map with nothing in it, as Iris does.
     */
    private static @Nullable Loaded builtin(final String path) {
        int color = switch (path) {
            case BUILTIN + "flat_normals" -> 0xFFFF8080;
            case BUILTIN + "no_specular", BUILTIN + "black" -> 0x00000000;
            default -> -1;
        };
        if (color == -1) return null;
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, color);
        Loaded loaded = new Loaded(new DynamicTexture(() -> "Metallum Extra " + path, image), false, false);
        LOADED.put(path, loaded);
        return loaded;
    }

    private static boolean flag(final Pattern pattern, final String text) {
        Matcher match = pattern.matcher(text);
        return match.find() && match.group(1).equals("true");
    }

    /**
     * Makes every picture the plan reads ready. Uploading a picture runs a copy, which cannot happen while a render pass is open (it would end the pass's
     * encoder), so this is called where no pass is: at the start of a frame.
     */
    public static void prepare(final ShaderPack pack, final com.metallumextra.shader.pack.IrisPlan plan) {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        paths.add(BUILTIN + "black");
        for (var input : plan.worldSlots()) if (input.texture() != null) paths.add(input.texture());
        for (var stage : com.metallumextra.shader.pack.ProgramSet.Stage.values()) {
            for (var step : plan.steps(stage)) {
                for (var input : step.inputs()) if (input.texture() != null) paths.add(input.texture());
            }
        }
        for (String path : paths) get(pack, path);
    }

    /** The picture's view, or null when it cannot be read (the pack's check has already said so). */
    public static @Nullable GpuTextureView view(final ShaderPack pack, final String path) {
        Loaded loaded = get(pack, path);
        return loaded == null ? null : loaded.texture.getTextureView();
    }

    public static GpuSampler sampler(final ShaderPack pack, final String path) {
        Loaded loaded = get(pack, path);
        FilterMode filter = loaded != null && loaded.blur ? FilterMode.LINEAR : FilterMode.NEAREST;
        return loaded != null && loaded.clamp ? RenderSystem.getSamplerCache().getClampToEdge(filter) : RenderSystem.getSamplerCache().getRepeat(filter);
    }

    /** Frees every picture; they are made again when next asked for. */
    public static synchronized void release() {
        for (Loaded loaded : LOADED.values()) loaded.texture.close();
        LOADED.clear();
        owner = null;
    }
}
