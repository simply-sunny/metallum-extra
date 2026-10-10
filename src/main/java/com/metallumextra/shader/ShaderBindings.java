package com.metallumextra.shader;

import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The uniform block and textures a pack's programs read without any pipeline declaring them.
 * <p>
 * The pipelines being drawn belong to the game, to Sodium or to other mods, and their draw code binds only what they declared. So the extra names
 * are let through when a shader is compiled, and Metallum's render pass binds them itself for any pipeline whose shaders turned out to use them.
 * The names are the ones {@link IrisWorldAdapter#boundSamplers} puts in place of Iris's: a program reads {@code shadowtex0}, and the pipeline sees
 * {@code MxShadowMap}.
 */
public final class ShaderBindings {
    public static final String SHADOW_MAP = "MxShadowMap";
    public static final String SHADOW_COLOR = "MxShadowColor";
    public static final String COLOR0 = "MxColor0";
    public static final String DEPTH0 = "MxDepth0";
    public static final String DEPTH1 = "MxDepth1";

    /** Prefix of the names of the slots for the other things the programs that draw the world read (see {@code IrisPlan#worldSlots}). */
    public static final String WORLD_SLOT = "MxWorld";
    private static final int FIXED = 7;

    /** In mask order: bit 0 is the uniform block of the standard uniforms, the rest textures; then the world's slots. */
    public static final String[] NAMES = names();

    private static String[] names() {
        String[] names = new String[FIXED + IrisPlan.WORLD_SLOTS];
        String[] fixed = {IrisUniforms.BLOCK, SHADOW_MAP, LightColorGrid.SAMPLER, SHADOW_COLOR, COLOR0, DEPTH0, DEPTH1};
        System.arraycopy(fixed, 0, names, 0, FIXED);
        for (int i = 0; i < IrisPlan.WORLD_SLOTS; i++) names[FIXED + i] = WORLD_SLOT + i;
        return names;
    }

    private static final long IRIS = 1;
    private static final long SHADOW = 1L << 1;
    private static final long LIGHT_COLORS = 1L << 2;
    private static final long SHADOW_COLOR_BIT = 1L << 3;
    private static final long COLOR0_BIT = 1L << 4;
    private static final long DEPTH0_BIT = 1L << 5;
    private static final long DEPTH1_BIT = 1L << 6;

    private static final Identifier BLOB_SHADOW = Identifier.withDefaultNamespace("pipeline/entity_shadow");

    private ShaderBindings() {
    }

    /** Added to Metallum's compiled pipeline: which of {@link #NAMES} its shaders use, as a bit mask. */
    public interface Pipeline {
        long metallumExtra$shaderBindings();
    }

    /**
     * Whether a pass should drop its draws with this pipeline.
     * <p>
     * While the shadow map is drawn, the game's own draw code runs a second time with its output pointed at the map. Only what the pack has a shadow
     * program for is kept: the copies {@link IrisWorld} made of the game's model pipelines, and the terrain's own. Anything else (text, lines, beams)
     * would scribble over the map from the camera's point of view. And once the sun casts real shadows, the round blob the game draws under each mob
     * is left out.
     */
    public static boolean skipsDraws(final RenderPipeline pipeline) {
        int phase = Shaders.phase();
        if (phase == Shaders.PHASE_SHADOW) {
            return !IrisWorld.isShadowVariant(pipeline) && !pipeline.getLocation().getPath().startsWith("pipeline/shadow_iris_");
        }
        return phase == Shaders.PHASE_WORLD && Shaders.castsShadows() && pipeline.getLocation().equals(BLOB_SHADOW);
    }

    public static void addUniforms(final List<BindGroupLayout.UniformDescription> uniforms) {
        if (Shaders.active()) uniforms.add(new BindGroupLayout.UniformDescription(IrisUniforms.BLOCK, UniformType.UNIFORM_BUFFER));
    }

    public static void addSamplers(final List<String> samplers) {
        if (!Shaders.active()) return;
        for (int i = 1; i < NAMES.length; i++) samplers.add(NAMES[i]);
    }

    /** Called by the render pass when it switches to a pipeline that uses any of the extra names. */
    public static void bind(final RenderPassBackend pass, final long mask) {
        if ((mask & IRIS) != 0) pass.setUniform(IrisUniforms.BLOCK, IrisPipeline.uniformBuffer());
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        if ((mask & SHADOW) != 0) pass.bindTexture(SHADOW_MAP, IrisPipeline.shadowDepth(), nearest);
        if ((mask & SHADOW_COLOR_BIT) != 0) pass.bindTexture(SHADOW_COLOR, IrisPipeline.shadowColor(), linear);
        if ((mask & COLOR0_BIT) != 0) pass.bindTexture(COLOR0, IrisPipeline.worldColor(), linear);
        if ((mask & DEPTH0_BIT) != 0) pass.bindTexture(DEPTH0, IrisPipeline.worldDepth(), nearest);
        if ((mask & DEPTH1_BIT) != 0) pass.bindTexture(DEPTH1, IrisPipeline.worldDepth(), nearest);
        if ((mask & LIGHT_COLORS) != 0) pass.bindTexture(LightColorGrid.SAMPLER, Shaders.lightColors().view(), linear);
        long slots = mask >>> FIXED;
        for (int slot = 0; slots != 0 && slot < IrisPlan.WORLD_SLOTS; slot++, slots >>>= 1) {
            if ((slots & 1) != 0) IrisPipeline.bindWorldSlot(pass, WORLD_SLOT + slot, slot);
        }
    }
}
