package com.metallumextra.shader;

import com.metallum.render.MetallumExtraNative;
import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.ProgramSet;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * The buffers of a standard pack: {@code colortex0} to {@code colortex15} and the depth textures, kept for as long as the
 * plan and the size of the screen stay the same.
 * <p>
 * Only what the plan's programs touch is allocated. A color buffer is two textures, one that programs read ("main") and
 * one they draw into ("alternate"); after a program draws, {@link #flip} swaps the two by exchanging references, so
 * nothing is copied. A buffer that is only ever drawn into in place (flip switched off) has no alternate.
 */
final class IrisTargets {
    private static final class Slot {
        final IrisPlan.Buffer spec;
        TextureTarget main;
        @Nullable TextureTarget alternate;

        Slot(final IrisPlan.Buffer spec) {
            this.spec = spec;
        }
    }

    private final Slot[] slots = new Slot[IrisPlan.COLOR_BUFFERS];
    /** One attachment for each group of packed buffers (see {@link IrisPlan#packGroups}): four 32-bit integers per pixel, a buffer in each. */
    private final java.util.List<TextureTarget> packed = new java.util.ArrayList<>();
    private @Nullable TextureTarget depthWithoutTranslucent;
    private @Nullable TextureTarget depthAll;
    /** The world's depth before the held item was drawn into it: {@code depthtex2}, which has the translucent things and not the hand. */
    private @Nullable TextureTarget depthWithoutHand;
    /** The pack's custom images ({@code image.<name>}) by name; a compute program writes them and programs read them (see {@link IrisPlan#customImages}). */
    private final java.util.Map<String, TextureTarget> images = new java.util.HashMap<>();
    /** The three-dimensional custom images: what the game is given, and the Metal textures behind it (see {@link #createVolume}). */
    private final java.util.Map<String, GpuTextureView> volumes = new java.util.HashMap<>();
    private final java.util.List<DynamicTexture> volumeStands = new java.util.ArrayList<>();
    private final java.util.List<MTLTexture> volumeTextures = new java.util.ArrayList<>();
    private @Nullable TextureTarget shadow;
    /** The terrain part of the shadow map, kept between frames; see {@link ShadowPass}. */
    private @Nullable TextureTarget shadowCache;
    /** The second color attachment of the shadow map ({@code shadowcolor1}) and the cached terrain part of it; null when no program uses it. */
    private @Nullable TextureTarget shadow1;
    private @Nullable TextureTarget shadowCache1;
    /** One empty texel, read in place of the shadow map while the shadow map is the image being drawn into, and when there is none. */
    private @Nullable TextureTarget emptyShadow;
    private int shadowResolution;
    private @Nullable IrisPlan plan;
    private int width;
    private int height;
    private int textures;
    private long bytes;

    /** Textures created so far over the life of this object, for the debug counters. */
    private int created;

    /** Whether the buffers are the ones this plan asked for (a pack that failed halfway through a frame is replaced by another, whose buffers do not exist yet). */
    boolean isFor(final IrisPlan other) {
        return other == plan;
    }

    /** Makes the buffers match the plan and the size, creating them if the plan or the size changed. */
    void configure(final IrisPlan newPlan, final int newWidth, final int newHeight) {
        if (newPlan == plan && newWidth == width && newHeight == height) return;
        release();
        plan = newPlan;
        width = Math.max(newWidth, 1);
        height = Math.max(newHeight, 1);
        for (Map.Entry<Integer, IrisPlan.Buffer> entry : newPlan.buffers().entrySet()) {
            Slot slot = new Slot(entry.getValue());
            int w = slot.spec.widthOn(width), h = slot.spec.heightOn(height);
            slot.main = create("colortex" + entry.getKey(), slot.spec.format(), w, h);
            if (needsAlternate(newPlan, entry.getKey())) slot.alternate = create("colortex" + entry.getKey() + " alternate", slot.spec.format(), w, h);
            slots[entry.getKey()] = slot;
        }
        for (int g = 0; g < newPlan.packGroups().size(); g++) packed.add(create("packed buffers " + g, GpuFormat.RGBA32_UINT, width, height));
        shadowResolution = newPlan.hasShadow() ? newPlan.shadowResolution() : 0;
        if (shadowResolution > 0) {
            shadow = createShadow("shadow map", shadowResolution);
            shadowCache = createShadow("shadow terrain", shadowResolution);
            if (newPlan.hasShadowColor1()) {
                shadow1 = create("shadowcolor1", GpuFormat.RGBA8_UNORM, shadowResolution, shadowResolution);
                shadowCache1 = create("shadowcolor1 terrain", GpuFormat.RGBA8_UNORM, shadowResolution, shadowResolution);
            }
        }
        emptyShadow = createShadow("empty shadow map", 1);
        if (newPlan.usesDepth(1)) depthWithoutTranslucent = createDepth("depthtex1");
        if (newPlan.usesDepth(2)) depthWithoutHand = createDepth("depthtex2");
        if (newPlan.usesDepth(0)) depthAll = createDepth("depthtex0");
        for (ProgramSet.CustomImage image : newPlan.customImages()) createImage(image);
        MetallumExtra.LOGGER.info("[Metallum Extra] Shader pack buffers: {} color, {} depth, {} MB at {}x{}", entryCount(), (depthAll != null ? 1 : 0) + (depthWithoutTranslucent != null ? 1 : 0),
                bytes / (1024 * 1024), width, height);
    }

    private int entryCount() {
        int count = 0;
        for (Slot slot : slots) if (slot != null) count++;
        return count;
    }

    private static boolean needsAlternate(final IrisPlan plan, final int buffer) {
        for (ProgramSet.Stage stage : ProgramSet.Stage.values()) {
            // The programs that draw the world write the main textures; nothing flips there.
            if (stage == ProgramSet.Stage.GBUFFERS || stage == ProgramSet.Stage.SHADOW) continue;
            for (IrisPlan.Step step : plan.steps(stage)) {
                for (int i = 0; i < step.writes().length; i++) {
                    if (step.writes()[i] == buffer && !step.inPlace()[i]) return true;
                }
            }
        }
        return false;
    }

    private TextureTarget create(final String name, final GpuFormat format, final int w, final int h) {
        TextureTarget target = new TextureTarget("Metallum Extra " + name, w, h, format, null);
        textures++;
        created++;
        bytes += (long) w * h * bytesPerPixel(format);
        clear(target, new float[] {0, 0, 0, 0});
        return target;
    }

    /** The shadow map: color for {@code shadowcolor0} and depth for {@code shadowtex0}, which is OpenGL depth (0 nearest the light) and starts empty (1). */
    private TextureTarget createShadow(final String name, final int size) {
        TextureTarget target = new TextureTarget("Metallum Extra " + name, size, size, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        textures++;
        created++;
        bytes += (long) size * size * 8;
        ShadowPass.clear(target, 1.0);
        return target;
    }

    /**
     * Makes a custom image in the format it names, cleared (if it says so) at the start of every frame. Two sizes make a two-dimensional image; three make a
     * three-dimensional one (see {@link #createVolume}).
     */
    private void createImage(final ProgramSet.CustomImage image) {
        GpuFormat format = IrisPlan.formatNamed(image.internal());
        if (format == null) return;
        if (image.sizes().length == 3) {
            createVolume(image);
            return;
        }
        if (image.sizes().length != 2) return;
        int w = image.relative() ? Math.max(1, Math.round(width * image.sizes()[0])) : Math.max(1, (int) image.sizes()[0]);
        int h = image.relative() ? Math.max(1, Math.round(height * image.sizes()[1])) : Math.max(1, (int) image.sizes()[1]);
        TextureTarget target = create("image " + image.name(), format, w, h);
        images.put(image.name(), target);
        if (image.clear()) clearEachFrame.add(image.name());
    }

    /**
     * Makes a three-dimensional custom image as the pack's own three-dimensional textures are made: a Metal texture of its own, with the game given a
     * one-pixel stand-in that the kernels and programs that use it are bound to the real one in place of (see {@link MetallumExtraNative}). It is not
     * cleared each frame, so its contents persist from frame to frame.
     */
    private void createVolume(final ProgramSet.CustomImage image) {
        MetallumExtraNative.Layout layout = MetallumExtraNative.layoutOf(image.internal());
        if (layout == null) return;
        int w = image.relative() ? Math.max(1, Math.round(width * image.sizes()[0])) : Math.max(1, (int) image.sizes()[0]);
        int h = image.relative() ? Math.max(1, Math.round(height * image.sizes()[1])) : Math.max(1, (int) image.sizes()[1]);
        int d = Math.max(1, (int) image.sizes()[2]);
        if (image.clear()) MetallumExtra.LOGGER.warn("[Metallum Extra] The three-dimensional image {} is not cleared each frame; its contents persist", image.name());
        try {
            MTLTexture real = MetallumExtraNative.create("Metallum Extra image " + image.name(), "TEXTURE_3D", image.internal(), w, h, d,
                    layout.channels(), layout.channelBytes(), new byte[w * h * d * layout.channels() * layout.channelBytes()]);
            NativeImage picture = new NativeImage(1, 1, false);
            picture.setPixel(0, 0, 0xFFFFFFFF);
            DynamicTexture stand = new DynamicTexture(() -> "Metallum Extra stand-in for image " + image.name(), picture);
            MetallumExtraNative.stand(stand.getTextureView(), real);
            volumes.put(image.name(), stand.getTextureView());
            volumeStands.add(stand);
            volumeTextures.add(real);
        } catch (RuntimeException e) {
            MetallumExtra.LOGGER.warn("[Metallum Extra] The three-dimensional image {} could not be made: {}", image.name(), e.getMessage(), e);
        }
    }

    /** The images cleared at the start of each frame, by name. */
    private final java.util.Set<String> clearEachFrame = new java.util.HashSet<>();

    /** The main texture of custom image {@code name}, or null if the pack declares none of that name (or it is one this version does not make). */
    @Nullable GpuTextureView image(final String name) {
        GpuTextureView volume = volumes.get(name);
        if (volume != null) return volume;
        TextureTarget target = images.get(name);
        return target == null ? null : target.getColorTextureView();
    }

    private TextureTarget createDepth(final String name) {
        TextureTarget target = new TextureTarget("Metallum Extra " + name, width, height, GpuFormat.R8_UNORM, GpuFormat.D32_FLOAT);
        textures++;
        created++;
        bytes += (long) width * height * 5;
        return target;
    }

    static int bytesPerPixel(final GpuFormat format) {
        return format.blockSize();
    }

    /** The color buffers' main textures are cleared the way their settings say, which is at the start of every frame. */
    void clearForFrame(final float[] fog) {
        for (Slot slot : slots) {
            if (slot != null && slot.spec.clear()) clear(slot.main, slot.spec.colorToClear(fog));
        }
        // What was packed is cleared to zero, which is what every buffer in a group is cleared to (see IrisPlan#packable).
        for (TextureTarget target : packed) clear(target, new float[] {0, 0, 0, 0});
        for (String name : clearEachFrame) clear(images.get(name), new float[] {0, 0, 0, 0});
    }

    /**
     * Cleared with a render pass of its own: Metallum carries out the clear call only when the texture is next used,
     * possibly in the middle of someone else's pass.
     */
    static void clear(final RenderTarget target, final float[] color) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Metallum Extra buffer clear",
                target.getColorTextureView(), Optional.of(new Vector4f(color[0], color[1], color[2], color[3])))) {
        }
    }

    /** The attachment of packed group {@code group}. */
    GpuTextureView packedView(final int group) {
        return packed.get(group).getColorTextureView();
    }

    /** What programs read of buffer {@code index}. */
    GpuTextureView read(final int index) {
        return slots[index].main.getColorTextureView();
    }

    TextureTarget readTarget(final int index) {
        return slots[index].main;
    }

    /** What a program draws buffer {@code index} into: the alternate texture, or the main one when the program draws in place. */
    GpuTextureView write(final int index, final boolean inPlace) {
        Slot slot = slots[index];
        return inPlace || slot.alternate == null ? slot.main.getColorTextureView() : slot.alternate.getColorTextureView();
    }

    /** After a program has drawn into the alternate texture: it becomes the one that is read. */
    void flip(final int index) {
        Slot slot = slots[index];
        if (slot.alternate == null) return;
        TextureTarget written = slot.alternate;
        slot.alternate = slot.main;
        slot.main = written;
    }

    boolean hasBuffer(final int index) {
        return slots[index] != null;
    }

    IrisPlan.Buffer spec(final int index) {
        return slots[index].spec;
    }

    /**
     * The depth texture for {@code depthtex<n>}: 0 is everything (with the hand), 1 is the opaque world, 2 is the world with the translucent things but without
     * the hand. A depth that was not made (no program reads it) is the nearest one that was.
     */
    GpuTextureView depth(final int n) {
        TextureTarget target = n == 0 ? depthAll : n == 2 && depthWithoutHand != null ? depthWithoutHand : depthWithoutTranslucent;
        return target.getDepthTextureView();
    }

    /** The target {@code depthtex2} is copied into before the held item is drawn; null when no program reads it. */
    @Nullable TextureTarget depthWithoutHand() {
        return depthWithoutHand;
    }

    /** The target whose depth is {@code depthtex0} (with translucent terrain) or {@code depthtex1} (without it); null when no program reads it. */
    @Nullable TextureTarget depthTarget(final boolean withTranslucent) {
        return withTranslucent ? depthAll : depthWithoutTranslucent;
    }

    @Nullable TextureTarget shadow() {
        return shadow;
    }

    @Nullable TextureTarget shadow1() {
        return shadow1;
    }

    @Nullable TextureTarget shadowCache1() {
        return shadowCache1;
    }

    @Nullable TextureTarget shadowCache() {
        return shadowCache;
    }

    TextureTarget emptyShadow() {
        return emptyShadow;
    }

    int shadowResolution() {
        return shadowResolution;
    }

    /** The size of the image a program draws when it writes buffer {@code index}. */
    int widthOf(final int index) {
        return slots[index].main.width;
    }

    int heightOf(final int index) {
        return slots[index].main.height;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int textures() {
        return textures;
    }

    long bytes() {
        return bytes;
    }

    int created() {
        return created;
    }

    /** Frees every texture. */
    void release() {
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;
            slot.main.destroyBuffers();
            if (slot.alternate != null) slot.alternate.destroyBuffers();
            slots[i] = null;
        }
        for (TextureTarget target : packed) target.destroyBuffers();
        packed.clear();
        for (TextureTarget target : images.values()) target.destroyBuffers();
        images.clear();
        volumes.clear();
        for (DynamicTexture stand : volumeStands) stand.close();
        volumeStands.clear();
        for (MTLTexture texture : volumeTextures) texture.release();
        volumeTextures.clear();
        clearEachFrame.clear();
        if (depthAll != null) depthAll.destroyBuffers();
        if (depthWithoutTranslucent != null) depthWithoutTranslucent.destroyBuffers();
        if (depthWithoutHand != null) depthWithoutHand.destroyBuffers();
        depthAll = null;
        depthWithoutTranslucent = null;
        depthWithoutHand = null;
        for (TextureTarget target : new TextureTarget[] {shadow, shadowCache, emptyShadow, shadow1, shadowCache1}) {
            if (target != null) target.destroyBuffers();
        }
        shadow = null;
        shadowCache = null;
        shadow1 = null;
        shadowCache1 = null;
        emptyShadow = null;
        shadowResolution = 0;
        plan = null;
        textures = 0;
        bytes = 0;
    }
}
