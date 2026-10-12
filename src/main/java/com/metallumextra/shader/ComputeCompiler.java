package com.metallumextra.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A compute program's GLSL turned into a Metal kernel: shaderc makes SPIR-V of it, SPIRV-Cross makes Metal source of that. The game's own graphics layer has
 * no compute stage, so this does what its pipeline compiler and Metallum do for the programs that draw, for this one stage.
 * <p>
 * Every resource gets its place in the Metal kernel here, and the result says which: a uniform block, a sampled texture (with its sampler) and an image each
 * go where the kernel expects them (see {@link Resource}).
 */
public final class ComputeCompiler {
    /** Metal Shading Language 4.0, as Metallum asks for. */
    private static final int MSL_VERSION = 0x40000;
    private static final int SHADERC_COMPUTE = 2;
    private static final int SPV_DECORATION_BINDING = 33;
    private static final int SPV_DECORATION_DESCRIPTOR_SET = 34;
    private static final int SPV_DIM_BUFFER = 5;

    private ComputeCompiler() {
    }

    public enum Kind {
        /** A block of uniforms, bound as a buffer. */
        BLOCK,
        /** A texture read through a sampler. */
        TEXTURE,
        /** An image that is read, written or both. */
        IMAGE,
        /** A buffer the kernel reads and writes. */
        BUFFER
    }

    /**
     * One thing the kernel takes: its name in the GLSL, what it is, the Metal slot (buffer or texture) and sampler slot (-1 for none) it is in, and the
     * binding the GLSL gave it ({@code layout(binding = n)}), which is how a buffer is found in shaders.properties.
     */
    public record Resource(String name, Kind kind, int slot, int sampler, int binding) {
    }

    public record Compiled(String metal, String entry, List<Resource> resources) {
    }

    /** @throws IllegalStateException with what shaderc or SPIRV-Cross said */
    public static Compiled compile(final String name, final String glsl) {
        ByteBuffer spirv = toSpirv(name, glsl);
        try {
            return toMetal(name, spirv);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static ByteBuffer toSpirv(final String name, final String glsl) {
        ByteBuffer source = MemoryUtil.memUTF8(glsl, false);
        ByteBuffer file = MemoryUtil.memUTF8(name);
        ByteBuffer entry = MemoryUtil.memUTF8("main");
        long options = Shaderc.shaderc_compile_options_initialize();
        long compiler = Shaderc.shaderc_compiler_initialize();
        try {
            // The same way the game compiles its own shaders: Vulkan 1.3, every uniform given a binding.
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
            Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, SHADERC_COMPUTE, file, entry, options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                    throw new IllegalStateException("Couldn't parse GLSL of the compute program " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                MemoryUtil.memCopy(bytes, copy);
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
            Shaderc.shaderc_compile_options_release(options);
            MemoryUtil.memFree(entry);
            MemoryUtil.memFree(file);
            MemoryUtil.memFree(source);
        }
    }

    private static Compiled toMetal(final String name, final ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(out), "spvc_context_create");
            long context = out.get(0);
            try {
                IntBuffer words = spirv.asIntBuffer();
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), out), "spvc_context_parse_spirv");
                long ir = out.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "spvc_context_create_compiler");
                long compiler = out.get(0);
                check(Spvc.spvc_compiler_create_compiler_options(compiler, out), "spvc_compiler_create_compiler_options");
                long options = out.get(0);
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "MSL_PLATFORM");
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION), "MSL_VERSION");
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true), "MSL_TEXTURE_BUFFER_NATIVE");
                check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                // Only what the kernel really uses is given a place.
                check(Spvc.spvc_compiler_get_active_interface_variables(compiler, out), "spvc_compiler_get_active_interface_variables");
                long active = out.get(0);
                check(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, active), "spvc_compiler_set_enabled_interface_variables");
                check(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, out, active), "spvc_compiler_create_shader_resources_for_active_variables");
                long resources = out.get(0);

                int stage = Spvc.spvc_compiler_get_execution_model(compiler);
                List<Resource> placed = new ArrayList<>();
                int buffers = 0, textures = 0, samplers = 0;
                SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
                for (int type : new int[] {Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE}) {
                    PointerBuffer list = stack.mallocPointer(1);
                    PointerBuffer size = stack.mallocPointer(1);
                    check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, size), "spvc_resources_get_resource_list_for_type");
                    int count = (int) size.get(0);
                    if (count == 0) continue;
                    SpvcReflectedResource.Buffer found = SpvcReflectedResource.create(list.get(0), count);
                    for (int i = 0; i < count; i++) {
                        SpvcReflectedResource resource = found.get(i);
                        int set = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), SPV_DECORATION_DESCRIPTOR_SET);
                        int at = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), SPV_DECORATION_BINDING);
                        Spvc.spvc_msl_resource_binding_init(binding);
                        binding.stage(stage).desc_set(set).binding(at);
                        Resource made;
                        switch (type) {
                            case Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> {
                                binding.msl_buffer(buffers);
                                made = new Resource(resource.nameString(), Kind.BLOCK, buffers++, -1, at);
                            }
                            case Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> {
                                binding.msl_buffer(buffers);
                                made = new Resource(resource.nameString(), Kind.BUFFER, buffers++, -1, at);
                            }
                            case Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> {
                                boolean texelBuffer = Spvc.spvc_type_get_image_dimension(Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id())) == SPV_DIM_BUFFER;
                                binding.msl_texture(textures);
                                if (!texelBuffer) binding.msl_sampler(samplers);
                                made = new Resource(resource.nameString(), Kind.TEXTURE, textures++, texelBuffer ? -1 : samplers++, at);
                            }
                            default -> {
                                binding.msl_texture(textures);
                                made = new Resource(resource.nameString(), Kind.IMAGE, textures++, -1, at);
                            }
                        }
                        check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "spvc_compiler_msl_add_resource_binding");
                        placed.add(made);
                    }
                }
                if (samplers > 16) throw new IllegalStateException("The compute program " + name + " reads " + samplers + " textures; Metal allows 16 samplers");
                check(Spvc.spvc_compiler_compile(compiler, out), "spvc_compiler_compile");
                String metal = MemoryUtil.memUTF8(out.get(0));
                metal = packSharedVectors(metal);
                return new Compiled(metal, "main0", List.copyOf(placed));
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static final java.util.regex.Pattern SHARED_VECTORS = java.util.regex.Pattern.compile(
            "(?m)^(\\s*threadgroup\\s+spvUnsafeArray<(?:spvUnsafeArray<)*)(float3|half3)((?:,\\s*\\d+>)+\\s+)(\\w+)(\\s*;)");

    /**
     * A vec3 in a work group's shared memory takes 12 bytes in GLSL and 16 in Metal's float3, which can make a program's memory more than Metal has (Photon's
     * 256 by 9 vectors are 36864 bytes, and 32768 is the most). So those arrays are made of packed vectors, and a row of one that a function is given is
     * unpacked first.
     */
    static String packSharedVectors(final String metal) {
        java.util.regex.Matcher match = SHARED_VECTORS.matcher(metal);
        java.util.List<String> names = new ArrayList<>();
        StringBuilder out = new StringBuilder(metal.length() + 256);
        while (match.find()) {
            names.add(match.group(4));
            match.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(match.group(1) + "packed_" + match.group(2) + match.group(3) + match.group(4) + match.group(5)));
        }
        if (names.isEmpty()) return metal;
        match.appendTail(out);
        String result = out.toString();
        for (String name : names) {
            result = result.replaceAll("(spvUnsafeArray<float3, \\d+>\\s+\\w+\\s*=\\s*)(" + java.util.regex.Pattern.quote(name) + "\\[[^\\]]+\\])(\\s*;)", "$1mx_unpack($2)$3");
        }
        String helper = "template <typename T, size_t N>\nspvUnsafeArray<float3, N> mx_unpack(threadgroup const spvUnsafeArray<T, N>& a)\n{\n"
                + "    spvUnsafeArray<float3, N> r;\n    for (size_t i = 0; i < N; i++) r[i] = float3(a[i]);\n    return r;\n}\n\n";
        int kernel = result.indexOf("kernel void main0");
        return kernel < 0 ? result : result.substring(0, kernel) + helper + result.substring(kernel);
    }

    private static void check(final int result, final String what) {
        if (result != Spvc.SPVC_SUCCESS) throw new IllegalStateException("SPIRV-Cross failed in " + what + " (" + result + ")");
    }
}
