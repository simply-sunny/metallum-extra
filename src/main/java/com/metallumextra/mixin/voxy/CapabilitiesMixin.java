package com.metallumextra.mixin.voxy;

import com.metallumextra.voxy.gl.VoxyShimmedGlCapabilities;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Voxy's {@code Capabilities} constructor is the very first thing that touches OpenGL on the client
 * (and, via {@code UploadStream.INSTANCE} and {@code ModelStore}, it is reached long after
 * {@code VoxyClient.initVoxyClient} has been cancelled on Metal). Instead of cancelling the
 * constructor — which would leave every final field at its default — this mixin lets it run to
 * completion against fake data: the context-bound {@code GL.getCapabilities()} is replaced with a
 * permissive instance, the shader-compile probes are answered directly, and the two integer queries
 * and two string queries return CPU-safe values. The resulting {@code Capabilities.INSTANCE} then
 * reports compute, indirect parameters and subgroups as available (matching the values the shimmed
 * renderer path expects) without a single real GL call.
 */
@Mixin(value = Capabilities.class, remap = false)
public abstract class CapabilitiesMixin {
    /** Replaces the LWJGL context probe with a CPU-built permissive object (never touches GL). */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities metallumExtra$shimmedCapabilities() {
        return VoxyShimmedGlCapabilities.get();
    }

    /** The int64 shader probe: answer "no" so downstream shader codegen sticks to plain types. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/gl/Capabilities;testShaderCompilesOk(Lme/cortex/voxy/client/core/gl/shader/ShaderType;Ljava/lang/String;)Z", ordinal = 0))
    private boolean metallumExtra$noInt64Shader(final ShaderType type, final String src) {
        return false;
    }

    /** The subgroup shader probe (only reached when the shimmed caps advertise subgroups): answer "yes". */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/gl/Capabilities;testShaderCompilesOk(Lme/cortex/voxy/client/core/gl/shader/ShaderType;Ljava/lang/String;)Z", ordinal = 1))
    private boolean metallumExtra$subgroupWorks(final ShaderType type, final String src) {
        return true;
    }

    /** GL_MAX_SHADER_STORAGE_BLOCK_SIZE: report 2 GiB, the sane default for the geometry buffers. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glGetInteger64(I)J"))
    private long metallumExtra$ssboMaxSize(final int pname) {
        return 1L << 31;
    }

    /**
     * GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT: 16. This feeds UploadStream's allocation
     * alignment; a small value keeps the 64 MB upload stream from wasting space per model upload.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glGetInteger(I)I"))
    private int metallumExtra$ssboAlignment(final int pname) {
        return 16;
    }

    /** GL_VERSION string read by the "is mesa" check: a CPU string with no driver substring. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glGetString(I)Ljava/lang/String;", ordinal = 0))
    private String metallumExtra$versionString(final int pname) {
        return "metallum-metal";
    }

    /** GL_VENDOR string read for the intel/nvidia/amd checks: none of them match. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glGetString(I)Ljava/lang/String;", ordinal = 1))
    private String metallumExtra$vendorString(final int pname) {
        return "metallum";
    }
}
