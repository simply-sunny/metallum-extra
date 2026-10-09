package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.ProgramSet;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
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
    private @Nullable TextureTarget depthWithoutTranslucent;
    private @Nullable TextureTarget depthAll;
    private @Nullable TextureTarget shadow;
    /** The terrain part of the shadow map, kept between frames; see {@link ShadowPass}. */
    private @Nullable TextureTarget shadowCache;
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
        shadowResolution = newPlan.hasShadow() ? newPlan.shadowResolution() : 0;
        if (shadowResolution > 0) {
            shadow = createShadow("shadow map", shadowResolution);
            shadowCache = createShadow("shadow terrain", shadowResolution);
        }
        emptyShadow = createShadow("empty shadow map", 1);
        if (newPlan.usesDepth(1) || newPlan.usesDepth(2)) depthWithoutTranslucent = createDepth("depthtex1");
        if (newPlan.usesDepth(0)) depthAll = createDepth("depthtex0");
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
        TextureTarget target = new TextureTarget("Metallum Extra " + name, w, h, false, format);
        textures++;
        created++;
        bytes += (long) w * h * bytesPerPixel(format);
        clear(target, new float[] {0, 0, 0, 0});
        return target;
    }

    /** The shadow map: color for {@code shadowcolor0} and depth for {@code shadowtex0}, which is OpenGL depth (0 nearest the light) and starts empty (1). */
    private TextureTarget createShadow(final String name, final int size) {
        TextureTarget target = new TextureTarget("Metallum Extra " + name, size, size, true, GpuFormat.RGBA8_UNORM);
        textures++;
        created++;
        bytes += (long) size * size * 8;
        ShadowPass.clear(target, 1.0);
        return target;
    }

    private TextureTarget createDepth(final String name) {
        TextureTarget target = new TextureTarget("Metallum Extra " + name, width, height, true, GpuFormat.R8_UNORM);
        textures++;
        created++;
        bytes += (long) width * height * 5;
        return target;
    }

    static int bytesPerPixel(final GpuFormat format) {
        String name = format.name();
        int channels = name.startsWith("RGBA") ? 4 : name.startsWith("RGB") ? 3 : name.startsWith("RG") ? 2 : 1;
        int bits = name.contains("32") ? 4 : name.contains("16") ? 2 : 1;
        return channels * bits;
    }

    /** The color buffers' main textures are cleared the way their settings say, which is at the start of every frame. */
    void clearForFrame(final float[] fog) {
        for (Slot slot : slots) {
            if (slot != null && slot.spec.clear()) clear(slot.main, slot.spec.colorToClear(fog));
        }
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

    /** The depth texture for {@code depthtex<n>}; {@code depthtex2} is the same as {@code depthtex1}, since the hand is drawn after everything here. */
    GpuTextureView depth(final int n) {
        TextureTarget target = n == 0 ? depthAll : depthWithoutTranslucent;
        return target.getDepthTextureView();
    }

    /** The target whose depth is {@code depthtex0} (with translucent terrain) or {@code depthtex1} (without it); null when no program reads it. */
    @Nullable TextureTarget depthTarget(final boolean withTranslucent) {
        return withTranslucent ? depthAll : depthWithoutTranslucent;
    }

    @Nullable TextureTarget shadow() {
        return shadow;
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
        if (depthAll != null) depthAll.destroyBuffers();
        if (depthWithoutTranslucent != null) depthWithoutTranslucent.destroyBuffers();
        depthAll = null;
        depthWithoutTranslucent = null;
        for (TextureTarget target : new TextureTarget[] {shadow, shadowCache, emptyShadow}) {
            if (target != null) target.destroyBuffers();
        }
        shadow = null;
        shadowCache = null;
        emptyShadow = null;
        shadowResolution = 0;
        plan = null;
        textures = 0;
        bytes = 0;
    }
}
