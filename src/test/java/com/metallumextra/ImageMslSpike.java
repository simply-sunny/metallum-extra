package com.metallumextra;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Spike: what Metallum's own SPIRV-Cross settings (MSL 4.0, decoration binding) make of storage images, shader storage buffers and atomics in a raster stage.
 * {@code ./gradlew imageMslSpike}
 */
public final class ImageMslSpike {
    private static final String VERTEX = """
            #version 430 core
            layout(binding = 70, r32ui) uniform coherent uimage3D mx_img_0;
            layout(binding = 71, rgba16f) uniform image2D mx_img_1;
            layout(std430, binding = 40) buffer MxBuf { uint counts[]; } mx_ssbo_0;
            layout(location = 0) in vec3 Position;
            void main() {
                ivec3 c = ivec3(Position);
                imageAtomicAdd(mx_img_0, c, 1u);
                imageStore(mx_img_1, c.xy, vec4(Position, 1.0));
                atomicAdd(mx_ssbo_0.counts[c.x & 15], 1u);
                gl_Position = vec4(Position, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 430 core
            layout(binding = 70, r32ui) uniform coherent uimage3D mx_img_0;
            layout(binding = 71, rgba16f) uniform image2D mx_img_1;
            layout(location = 0) out vec4 outColor;
            void main() {
                uint v = imageLoad(mx_img_0, ivec3(gl_FragCoord.xyz)).r;
                outColor = imageLoad(mx_img_1, ivec2(gl_FragCoord.xy)) + float(v);
            }
            """;

    public static void main(final String[] args) throws Exception {
        for (boolean vertex : new boolean[] {true, false}) {
            System.out.println("==== " + (vertex ? "vertex" : "fragment"));
            System.out.println(toMsl(spirv(vertex ? VERTEX : FRAGMENT, vertex), vertex));
        }
    }

    private static ByteBuffer spirv(final String source, final boolean vertex) {
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
        Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
        long compiler = Shaderc.shaderc_compiler_initialize();
        ByteBuffer src = MemoryUtil.memUTF8(source, false);
        long result = Shaderc.shaderc_compile_into_spv(compiler, src, vertex ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader, MemoryUtil.memUTF8("spike"), MemoryUtil.memUTF8("main"), options);
        if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
            throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
        }
        ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
        ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
        copy.put(bytes).flip();
        return copy;
    }

    private static String toMsl(final ByteBuffer spirv, final boolean vertex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            Spvc.spvc_context_create(p);
            long context = p.get(0);
            Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, p);
            long ir = p.get(0);
            check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), context);
            long compiler = p.get(0);
            Spvc.spvc_compiler_create_compiler_options(compiler, p);
            long options = p.get(0);
            Spvc.spvc_compiler_options_set_uint(options, 134217759, 1); // MSL_PLATFORM macOS
            Spvc.spvc_compiler_options_set_uint(options, 134217745, 262144);
            Spvc.spvc_compiler_options_set_bool(options, 134217777, true);
            Spvc.spvc_compiler_options_set_bool(options, 134217762, true);
            Spvc.spvc_compiler_install_compiler_options(compiler, options);
            check(Spvc.spvc_compiler_compile(compiler, p), context);
            return MemoryUtil.memUTF8(p.get(0));
        }
    }

    private static void check(final int status, final long context) {
        if (status != 0) throw new IllegalStateException("spvc " + status + ": " + Spvc.spvc_context_get_last_error_string(context));
    }
}
