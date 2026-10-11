package com.metallum.render;

import com.metallumextra.ExtraConfig;
import com.metallumextra.SamplerSlots;
import com.metallumextra.shader.pack.TranslationCache;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

/**
 * Lives in Metallum's package (inside the Metallum Extra jar) so it can reach Metallum's
 * package-private classes. Fabric loads all mods with one class loader, so this works at runtime.
 * Keep this class tiny: everything here depends on Metallum internals, and has to be right for every
 * Metallum release the mod accepts.
 */
public final class MetallumExtraBridge {
    private MetallumExtraBridge() {
    }

    /** Whether Metallum wrote this buffer's initial data straight into CPU-visible memory instead of recording a GPU copy. Only for the profiler's count. */
    public static boolean writtenDirectly(final GpuBuffer buffer) {
        return ((MetalGpuBuffer) buffer).isCpuAccessible();
    }

    /** Which sampler slot each of a pipeline's textures uses in each stage; see {@link SamplerSlots}. */
    public static byte @Nullable [] samplerSlots(final Object compiledPipeline, final String pipelineName) {
        int[] vertex = new int[0];
        int[] fragment = new int[0];
        String[] vertexNames = new String[0];
        String[] fragmentNames = new String[0];
        // Metallum lists a pipeline's resources in binding order, so these come out sorted.
        for (MetalCompiledRenderPipeline.ResourceBinding resource : ((MetalCompiledRenderPipeline) compiledPipeline).resources()) {
            if (resource.kind() != MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) continue;
            if ((resource.stageMask() & SamplerSlots.STAGE_VERTEX) != 0) {
                vertex = append(vertex, resource.bindingIndex());
                vertexNames = Arrays.copyOf(vertexNames, vertexNames.length + 1);
                vertexNames[vertexNames.length - 1] = resource.name();
            }
            if ((resource.stageMask() & SamplerSlots.STAGE_FRAGMENT) != 0) {
                fragment = append(fragment, resource.bindingIndex());
                fragmentNames = Arrays.copyOf(fragmentNames, fragmentNames.length + 1);
                fragmentNames[fragmentNames.length - 1] = resource.name();
            }
        }
        return SamplerSlots.table(vertex, vertexNames, fragment, fragmentNames, pipelineName);
    }

    /** Bit {@code i} is set when the pipeline's shaders use the uniform block or texture called {@code names[i]}. */
    public static long resourceMask(final Object compiledPipeline, final String[] names) {
        MetalCompiledRenderPipeline pipeline = (MetalCompiledRenderPipeline) compiledPipeline;
        long mask = 0;
        for (int i = 0; i < names.length; i++) {
            if (pipeline.resource(names[i]) != null) mask |= 1L << i;
        }
        return mask;
    }

    /** The number the game's frontend gives the uniform block or texture called {@code name} in this pipeline (the index its render pass binds it under). */
    public static int uniformIndex(final Object compiledPipeline, final String name) {
        MetalCompiledRenderPipeline.ResourceBinding resource = ((MetalCompiledRenderPipeline) compiledPipeline).resource(name);
        return resource == null ? -1 : resource.bindingIndex();
    }

    private static int[] append(final int[] values, final int value) {
        int[] grown = Arrays.copyOf(values, values.length + 1);
        grown[values.length] = value;
        return grown;
    }

    /** Set to anything to convert every shader every time, as Metallum does by itself. */
    private static final boolean NO_MSL_CACHE = System.getProperty("metallumextra.noMslCache") != null;
    private static volatile @Nullable String cacheSalt;

    /** The cache key of the conversion in progress on this thread, kept from {@link #cachedTranslation} to {@link #storeTranslation}. */
    private static final ThreadLocal<String> PENDING_KEY = new ThreadLocal<>();

    /**
     * Start of Metallum's {@code spirvToMsl}: the result of a conversion already done, read back from the disk cache
     * (see {@link TranslationCache}), or null if SPIRV-Cross has to run. {@code metalIndices} is Metallum's private
     * record of where each resource goes in the Metal shader; it is part of what the conversion's text depends on.
     */
    public static @Nullable Object cachedTranslation(final ByteBuffer spirv, final Object metalIndices, final Map<Integer, GpuFormat> attributeFormats,
                                                     final boolean enableFragDepth) {
        PENDING_KEY.remove();
        SamplerSlots.metalSlots(slotsOf(metalIndices));
        if (NO_MSL_CACHE) return null;
        String key = TranslationCache.key(salt() + SamplerSlots.fingerprint(), spirv, indicesOf(metalIndices), attributeFormats, enableFragDepth);
        TranslationCache.Msl cached = TranslationCache.shared().lookup(key);
        if (cached != null) {
            return new MetalCrossShaderCompiler.MslShader(cached.source(), cached.hasPushConstants(), cached.activeResources());
        }
        PENDING_KEY.set(key);
        return null;
    }

    /** The Metal slot of each binding, from Metallum's private record. */
    private static int @Nullable [] slotsOf(final Object metalIndices) {
        try {
            java.lang.reflect.Method byBinding = metalIndices.getClass().getDeclaredMethod("byBinding");
            byBinding.setAccessible(true);
            return (int[]) byBinding.invoke(metalIndices);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Text of {@code MetalIndices[byBinding, pushConstants]}: the record is private to Metallum, so it is read through reflection. */
    private static String indicesOf(final Object metalIndices) {
        try {
            java.lang.reflect.Method byBinding = metalIndices.getClass().getDeclaredMethod("byBinding");
            java.lang.reflect.Method pushConstants = metalIndices.getClass().getDeclaredMethod("pushConstants");
            byBinding.setAccessible(true);
            pushConstants.setAccessible(true);
            return Arrays.toString((int[]) byBinding.invoke(metalIndices)) + "/" + pushConstants.invoke(metalIndices);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Metallum's resource numbering cannot be read", e);
        }
    }

    /** End of {@code spirvToMsl}: keeps what SPIRV-Cross made, if {@link #cachedTranslation} found nothing. Returns the shader to use in its place when it had to be changed, else null. */
    public static @Nullable Object storeTranslation(final @Nullable Object result) {
        if (!(result instanceof MetalCrossShaderCompiler.MslShader converted)) return null;
        MetalCrossShaderCompiler.MslShader use = converted;
        if (SamplerSlots.sharing()) {
            String shared = SamplerSlots.shareSamplers(converted.source());
            if (!shared.equals(converted.source())) use = new MetalCrossShaderCompiler.MslShader(shared, converted.hasPushConstants(), converted.activeResources());
        }
        String key = PENDING_KEY.get();
        if (key != null) {
            PENDING_KEY.remove();
            TranslationCache.shared().store(key, new TranslationCache.Msl(use.source(), use.hasPushConstants(), use.activeResources()));
        }
        return use == converted ? null : use;
    }

    /**
     * The things besides the shader that decide what the conversion gives: this mod and Metallum (which sets the
     * conversion's options and numbers the resources), SPIRV-Cross as shipped with LWJGL, and the one setting of this
     * mod that changes the text (see {@link SamplerSlots}).
     */
    private static String salt() {
        String salt = cacheSalt;
        if (salt == null) {
            salt = "extra=" + version("metallum-extra") + ";metallum=" + version("metallum")
                    + ";lwjgl=" + org.lwjgl.Version.getVersion() + ";manyTextures=" + ExtraConfig.get().manyTextures;
            cacheSalt = salt;
        }
        return salt;
    }

    private static String version(final String modId) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
