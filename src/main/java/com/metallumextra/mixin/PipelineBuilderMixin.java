package com.metallumextra.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallumextra.FrameProfiler;
import com.metallumextra.ResourceLog;
import com.metallumextra.shader.PipelineErrors;
import com.metallumextra.shader.ShaderBindings;
import com.metallumextra.shader.ShaderSources;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The game's pipeline compiler. Profiler: which pipeline is being compiled and what compiling its GLSL costs. Shaders: every shader is first looked
 * for among this mod's own (see {@link ShaderSources}), the shader pipeline's own uniform block and textures are let through where the compiler
 * checks a shader's resources against what its pipeline declares (see {@link ShaderBindings}), and what a shader that does not compile said is kept.
 */
@Mixin(PipelineBuilder.class)
public abstract class PipelineBuilderMixin {
    private static final ThreadLocal<long[]> COMPILE_START = ThreadLocal.withInitial(() -> new long[1]);

    @Inject(method = "compilePipeline", at = @At("HEAD"))
    private void metallumExtra$pipelineRequested(final RenderPipeline pipeline, final ShaderSource shaderSource, final Executor executor,
                                                 final CallbackInfoReturnable<CompletableFuture<?>> cir) {
        FrameProfiler.pipelineRequested(pipeline);
    }

    @ModifyVariable(method = "compilePipeline", at = @At("HEAD"), argsOnly = true)
    private ShaderSource metallumExtra$ownShadersFirst(final ShaderSource shaderSource) {
        return ShaderSources.wrap(shaderSource);
    }

    /** What compiling GLSL to SPIR-V costs, for the resource log, and the reason a shader of a pack failed. */
    @WrapOperation(method = "generateBackendCreateInfo",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;compileToSpv(Ljava/lang/String;Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;Lnet/minecraft/client/renderer/ShaderDefines;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)Lcom/mojang/renderpearl/backend/api/SpvModule;"))
    private SpvModule metallumExtra$compileToSpv(final GlslCompiler compiler, final String name, final String source, final ShaderType type, final ShaderDefines defines,
                                                 final ShaderSource shaderSource, final Operation<SpvModule> original) throws ShaderCompileException {
        long start = System.nanoTime();
        try {
            return original.call(compiler, name, source, type, defines, shaderSource);
        } catch (Exception e) {
            // A shader that does not compile is a ShaderCompileException (checked), which the compiler's caller deals with.
            PipelineErrors.remember(e);
            throw PipelineErrors.rethrow(e);
        } finally {
            long ns = System.nanoTime() - start;
            if (ns > 2_000_000L) ResourceLog.timed("glsl-to-spirv", name + " " + type, ns);
        }
    }

    @WrapOperation(method = "generateBackendCreateInfo",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/pipeline/BindGroupLayout;flattenUniforms(Ljava/util/List;)Ljava/util/List;"))
    private List<BindGroupLayout.UniformDescription> metallumExtra$allowShaderUniforms(final List<BindGroupLayout> layouts,
                                                                                       final Operation<List<BindGroupLayout.UniformDescription>> original) {
        List<BindGroupLayout.UniformDescription> uniforms = original.call(layouts);
        ShaderBindings.addUniforms(uniforms);
        return uniforms;
    }

    /**
     * The game refuses 1D and 3D textures: the dimension a shader's sampler has is read here, once per resource, to be checked against what the pipeline
     * declares (a 2D sampler). A 3D texture is bound for a placeholder (see {@code MetallumExtraNative}), so the check is told it is a plain texture.
     */
    @ModifyExpressionValue(method = "generateBackendCreateInfo",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/IntList;getInt(I)I", ordinal = 3))
    private int metallumExtra$anyTextureDimension(final int dimensions) {
        return dimensions == 0 || dimensions == 2 ? 1 : dimensions;
    }
}
