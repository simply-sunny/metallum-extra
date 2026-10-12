package com.metallumextra.shader;

import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.util.function.ToIntFunction;
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
    public static final String SHADOW_COLOR1 = "MxShadowColor1";
    /** A white texel, read in place of a texture the pipeline being drawn with does not have (the atlas of the clouds). */
    public static final String WHITE = "MxWhite";
    public static final String COLOR0 = "MxColor0";
    public static final String DEPTH0 = "MxDepth0";
    public static final String DEPTH1 = "MxDepth1";
    /** The world's depth without the held item (depthtex2). */
    public static final String DEPTH2 = "MxDepth2";

    /** Prefix of the names of the slots for the other things the programs that draw the world read (see {@code IrisPlan#worldSlots}). */
    public static final String WORLD_SLOT = "MxWorld";
    private static final int FIXED = 10;

    /** In mask order: bit 0 is the uniform block of the standard uniforms, the rest textures; then the world's slots. */
    public static final String[] NAMES = names();

    private static String[] names() {
        String[] names = new String[FIXED + IrisPlan.WORLD_SLOTS];
        String[] fixed = {IrisUniforms.BLOCK, SHADOW_MAP, LightColorGrid.SAMPLER, SHADOW_COLOR, COLOR0, DEPTH0, DEPTH1, SHADOW_COLOR1, WHITE, DEPTH2};
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
    private static final long SHADOW_COLOR1_BIT = 1L << 7;
    private static final long WHITE_BIT = 1L << 8;
    private static final long DEPTH2_BIT = 1L << 9;

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

    /** The pipeline compiler checks what a shader reads against what its pipeline declares; the extra names are added to what every pipeline is taken to declare. */
    public static void addUniforms(final List<BindGroupLayout.UniformDescription> uniforms) {
        if (!Shaders.active()) return;
        uniforms.add(new BindGroupLayout.UniformDescription(IrisUniforms.BLOCK, UniformType.UNIFORM_BUFFER));
        for (int i = 1; i < NAMES.length; i++) uniforms.add(new BindGroupLayout.UniformDescription(NAMES[i], UniformType.COMBINED_IMAGE_SAMPLER));
    }

    /**
     * Gives a backend render pass things by name. The pass itself knows its resources by number; {@code indexOf} is the pipeline's numbering,
     * which the game's own render pass uses as well.
     */
    public static final class Binder {
        private final RenderPassBackend pass;
        private final ToIntFunction<String> indexOf;

        private Binder(final RenderPassBackend pass, final ToIntFunction<String> indexOf) {
            this.pass = pass;
            this.indexOf = indexOf;
        }

        public void setUniform(final String name, final GpuBufferSlice value) {
            int index = this.indexOf.applyAsInt(name);
            if (index >= 0) this.pass.setUniform(index, value);
        }

        public void bindTexture(final String name, final GpuTextureView view, final GpuSampler sampler) {
            int index = this.indexOf.applyAsInt(name);
            if (index >= 0) this.pass.setUniform(index, new TextureViewAndSampler(view, sampler));
        }
    }

    /** Called by the render pass when it switches to a pipeline that uses any of the extra names. */
    public static void bind(final RenderPassBackend backendPass, final ToIntFunction<String> indexOf, final long mask) {
        Binder pass = new Binder(backendPass, indexOf);
        if ((mask & IRIS) != 0) pass.setUniform(IrisUniforms.BLOCK, IrisPipeline.uniformBuffer().slice());
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        if ((mask & SHADOW) != 0) pass.bindTexture(SHADOW_MAP, IrisPipeline.shadowDepth(), nearest);
        if ((mask & SHADOW_COLOR_BIT) != 0) pass.bindTexture(SHADOW_COLOR, IrisPipeline.shadowColor(), linear);
        if ((mask & SHADOW_COLOR1_BIT) != 0) pass.bindTexture(SHADOW_COLOR1, IrisPipeline.shadowColor1(), linear);
        if ((mask & WHITE_BIT) != 0) {
            var pack = com.metallumextra.shader.pack.PackManager.active();
            var white = PackTextures.view(pack, PackTextures.BUILTIN + "white");
            if (white != null) pass.bindTexture(WHITE, white, linear);
        }
        if ((mask & COLOR0_BIT) != 0) pass.bindTexture(COLOR0, IrisPipeline.worldColor(), linear);
        if ((mask & DEPTH0_BIT) != 0) pass.bindTexture(DEPTH0, IrisPipeline.worldDepth(), nearest);
        if ((mask & DEPTH1_BIT) != 0) pass.bindTexture(DEPTH1, IrisPipeline.worldDepth(), nearest);
        if ((mask & DEPTH2_BIT) != 0) pass.bindTexture(DEPTH2, IrisPipeline.worldDepthWithoutHand(), nearest);
        if ((mask & LIGHT_COLORS) != 0) pass.bindTexture(LightColorGrid.SAMPLER, Shaders.lightColors().view(), linear);
        long slots = mask >>> FIXED;
        for (int slot = 0; slots != 0 && slot < IrisPlan.WORLD_SLOTS; slot++, slots >>>= 1) {
            if ((slots & 1) != 0) IrisPipeline.bindWorldSlot(pass, WORLD_SLOT + slot, slot);
        }
    }
}
