package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.ShaderPack;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
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
    private record Loaded(DynamicTexture texture, boolean blur, boolean clamp, boolean raw) {
    }

    private static final Map<String, Loaded> LOADED = new HashMap<>();
    /** Built-in pictures outlive pack changes, so they are never remade while a pass is recording. */
    private static final Map<String, Loaded> OWN = new HashMap<>();
    private static @Nullable ShaderPack owner;
    private static final Pattern BLUR = Pattern.compile("\"blur\"\\s*:\\s*(true|false)");
    private static final Pattern CLAMP = Pattern.compile("\"clamp\"\\s*:\\s*(true|false)");

    private PackTextures() {
    }

    private static synchronized @Nullable Loaded get(final ShaderPack pack, final String path) {
        if (path.startsWith(BUILTIN) && path.indexOf(' ') < 0) {
            Loaded own = OWN.get(path);
            if (own == null) {
                own = builtin(path);
                if (own != null) OWN.put(path, own);
            }
            LOADED.remove(path);
            return own;
        }
        if (owner != pack) {
            release();
            owner = pack;
        }
        Loaded loaded = LOADED.get(path);
        if (loaded != null) return loaded;
        if (path.startsWith(BUILTIN)) return builtin(path);
        com.metallumextra.shader.pack.TextureSpec spec = com.metallumextra.shader.pack.TextureSpec.parse(path);
        if (spec != null) return raw(pack, path, spec);
        byte[] bytes = path.matches("[a-z0-9_.-]+:.+") ? gameResource(path) : pack.bytes(path);
        if (bytes == null) return null;
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes))) {
            // The texture keeps its own copy of the pixels.
            NativeImage copy = new NativeImage(image.getWidth(), image.getHeight(), false);
            copy.copyFrom(image);
            DynamicTexture texture = new DynamicTexture(() -> "Metallum Extra pack texture " + path, copy);
            String meta = pack.read(path + ".mcmeta");
            boolean blur = meta == null || !meta.contains("\"blur\"") || flag(BLUR, meta);
            boolean clamp = meta != null && flag(CLAMP, meta);
            loaded = new Loaded(texture, blur, clamp, false);
            LOADED.put(path, loaded);
            return loaded;
        } catch (IOException | RuntimeException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Shader pack {}: could not read the picture {}", pack.name(), path, e);
            return null;
        }
    }

    /**
     * A file of raw numbers (a 3D noise, a lookup table) as {@code shaders.properties} describes it. The game's own texture kinds have no 3D, so the game is
     * given a one-pixel picture to hand to the programs, and the real texture is bound in its place (see {@link com.metallum.render.MetallumExtraNative}).
     */
    private static @Nullable Loaded raw(final ShaderPack pack, final String key, final com.metallumextra.shader.pack.TextureSpec spec) {
        // An image the pack declares (image.* in shaders.properties) that nothing writes to: zeros.
        byte[] bytes = spec.path().equals("mx:zero") ? new byte[spec.width() * spec.height() * spec.depth() * spec.sourceChannels() * spec.sourceBytes()] : pack.bytes(spec.path());
        if (bytes == null) return null;
        try {
            Object real = com.metallum.render.MetallumExtraNative.create("Metallum Extra pack texture " + spec.path(), spec.target(), spec.format(), spec.width(), spec.height(), spec.depth(),
                    spec.sourceChannels(), spec.sourceBytes(), bytes);
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, 0xFFFFFFFF);
            DynamicTexture stand = new DynamicTexture(() -> "Metallum Extra stand-in for " + spec.path(), image);
            com.metallum.render.MetallumExtraNative.stand(stand.getTextureView(), (io.github.kokodio.metaljvm.metal.MTLTexture) real);
            Loaded loaded = new Loaded(stand, true, false, true);
            LOADED.put(key, loaded);
            return loaded;
        } catch (RuntimeException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] Shader pack {}: could not make the texture {}", pack.name(), spec.path(), e);
            return null;
        }
    }

    /** A picture of the game's own resources ({@code minecraft:textures/environment/clouds.png}), which a pack may ask for by that name. */
    private static byte @Nullable [] gameResource(final String path) {
        try {
            var id = net.minecraft.resources.Identifier.parse(path);
            var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) return null;
            try (var in = resource.get().open()) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
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
        if (path.equals(BUILTIN + "noise")) {
            NativeImage image = new NativeImage(256, 256, false);
            java.util.Random random = new java.util.Random(0x4D585F4E4F495345L);
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) image.setPixel(x, y, random.nextInt() | 0xFF000000);
            Loaded noise = new Loaded(new DynamicTexture(() -> "Metallum Extra " + path, image), true, false, false);
            LOADED.put(path, noise);
            return noise;
        }
        Integer color = switch (path) {
            case BUILTIN + "flat_normals" -> 0xFFFF8080;
            case BUILTIN + "no_specular", BUILTIN + "black" -> 0x00000000;
            case BUILTIN + "white" -> 0xFFFFFFFF;
            default -> null;
        };
        if (color == null) return null;
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, color);
        Loaded loaded = new Loaded(new DynamicTexture(() -> "Metallum Extra " + path, image), false, false, false);
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
        paths.add(BUILTIN + "white");
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

    /**
     * How a picture is read, without loading it: 0 filtered and clamped, 1 not filtered and clamped, 2 filtered and repeating, 3 not filtered and repeating.
     * The same decision {@link #sampler} makes once the picture is loaded.
     */
    public static int kind(final ShaderPack pack, final String path) {
        boolean blur, clamp;
        if (com.metallumextra.shader.pack.TextureSpec.parse(path) != null) {
            blur = true;
            clamp = false;
        } else if (path.equals(BUILTIN + "noise")) {
            blur = true;
            clamp = false;
        } else if (path.startsWith(BUILTIN)) {
            blur = false;
            clamp = false;
        } else {
            String meta = pack.read(path + ".mcmeta");
            blur = meta == null || !meta.contains("\"blur\"") || flag(BLUR, meta);
            clamp = meta != null && flag(CLAMP, meta);
        }
        return (blur ? 0 : 1) + (clamp ? 0 : 2);
    }

    /** Frees every picture; they are made again when next asked for. */
    public static synchronized void release() {
        for (Loaded loaded : LOADED.values()) {
            if (loaded.raw) com.metallum.render.MetallumExtraNative.forget(loaded.texture.getTextureView());
            loaded.texture.close();
        }
        LOADED.clear();
        owner = null;
    }
}
