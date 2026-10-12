package com.metallumextra.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallumextra.NonBlockingPresent;
import com.metallumextra.ResourceLog;
import io.github.kokodio.metaljvm.metal.MTLCompileOptions;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLMathMode;
import io.github.kokodio.metaljvm.metal.MTLRenderPipelineDescriptor;
import io.github.kokodio.metaljvm.metal.MTLRenderPipelineState;
import io.github.kokodio.metaljvm.quartzcore.CAMetalDrawable;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Fix: never block the render thread waiting for a swapchain image when vsync is off. */
@Mixin(targets = "com.metallum.render.MetalUtilities", remap = false)
public abstract class MetalUtilitiesMixin {
    /** Metal compiles with fast-math by default, which a pack's float math does not get from OpenGL; see {@link #metallumExtra$openGlMath()}. */
    private static MTLCompileOptions metallumExtra$options;

    /**
     * Times the two Metal compiles that every pipeline pays on each launch: MSL source to a library (the Metal compiler's front end), and the library's
     * function to a pipeline state (the back end). Reported in the resource log as {@code pipeline-library} and {@code pipeline-state}.
     */
    @WrapMethod(method = "newLibrary")
    private static MTLLibrary metallumExtra$timeLibrary(final MTLDevice device, final String source, final Operation<MTLLibrary> original) {
        long start = System.nanoTime();
        try {
            return original.call(device, source);
        } finally {
            ResourceLog.timed("pipeline-library", "MSL source to Metal library", System.nanoTime() - start);
        }
    }

    @WrapMethod(method = "newRenderPipelineState")
    private static MTLRenderPipelineState metallumExtra$timeState(final MTLDevice device, final MTLRenderPipelineDescriptor descriptor,
                                                                  final Operation<MTLRenderPipelineState> original) {
        long start = System.nanoTime();
        try {
            return original.call(device, descriptor);
        } finally {
            ResourceLog.timed("pipeline-state", "render pipeline state", System.nanoTime() - start);
        }
    }

    @Redirect(method = "encodePresentTextureToDrawable",
            at = @At(value = "INVOKE", target = "Lio/github/kokodio/metaljvm/quartzcore/CAMetalLayer;nextDrawable()Lio/github/kokodio/metaljvm/quartzcore/CAMetalDrawable;"))
    private static CAMetalDrawable metallumExtra$nextDrawable(final CAMetalLayer layer) {
        return NonBlockingPresent.nextDrawable(layer);
    }

    /**
     * Every library made from MSL (the pipelines' and the compute kernels') is compiled without fast-math, so that the math of a pack does what OpenGL
     * does: NaN and infinity keep their meaning, a division or a sqrt is not reordered or approximated. Metallum passes no options (null), which Metal reads
     * as fast-math on. The options are made once and kept for the life of the game.
     */
    @ModifyArg(method = "newLibrary", index = 1, at = @At(value = "INVOKE",
            target = "Lio/github/kokodio/metaljvm/metal/MTLDevice;newLibraryWithSource(Ljava/lang/String;Lio/github/kokodio/metaljvm/metal/MTLCompileOptions;)Lio/github/kokodio/metaljvm/metal/MTLLibrary;"))
    private static MTLCompileOptions metallumExtra$openGlMath(final MTLCompileOptions options) {
        if (metallumExtra$options == null) {
            MTLCompileOptions made = MTLCompileOptions.alloc().init();
            made.setFastMathEnabled(false);
            made.setMathMode(MTLMathMode.Safe);
            metallumExtra$options = made;
        }
        return metallumExtra$options;
    }
}
