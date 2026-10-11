package com.metallumextra;

import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.LegacyGlsl;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ShaderPack;
import com.metallumextra.shader.pack.TranslationCache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small architecture checks that need the project's compile classpath, but do not start Minecraft. */
public final class ArchitectureChecks {
    private static int checks;

    private ArchitectureChecks() {}

    public static void main(final String[] args) throws Exception {
        stageOrderingAndBufferFlips();
        legacyGlslRewrite();
        translationCacheInvalidation();
        System.out.println("Architecture checks passed: " + checks);
    }

    private static void stageOrderingAndBufferFlips() throws PackException {
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : List.of("composite10", "composite", "composite2", "final")) {
            files.put(name + ".vsh", VERTEX);
            files.put(name + ".fsh", name.equals("final") ? finalFragment() : fragment());
        }

        IrisPlan plan = IrisPlan.build(new MemoryPack("ArchitectureStages", files), null);
        List<String> order = plan.steps(ProgramSet.Stage.COMPOSITE).stream().map(step -> step.program().name()).toList();
        check("numbered composite passes sort numerically", order.equals(List.of("composite", "composite2", "composite10")));
        check("final follows the composite passes", plan.finalStep() != null && plan.finalStep().program().name().equals("final"));

        Map<String, String> writes = new LinkedHashMap<>();
        writes.put("composite.vsh", VERTEX);
        writes.put("composite.fsh", "#version 330\nin vec2 texcoord;\n/* RENDERTARGETS: 0,1 */\n"
                + "layout(location = 0) out vec4 a; layout(location = 1) out vec4 b;\n"
                + "void main() { a = vec4(0); b = vec4(texcoord, 0.0, 1.0); }\n");
        IrisPlan defaultFlip = IrisPlan.build(new MemoryPack("ArchitectureDefaultFlip", writes), null);
        IrisPlan.Step defaultStep = defaultFlip.steps(ProgramSet.Stage.COMPOSITE).get(0);
        check("buffers flip by default", !defaultStep.inPlace()[0] && !defaultStep.inPlace()[1]);

        writes.put("shaders.properties", "flip.composite.colortex1=false\n");
        IrisPlan oneFlipOff = IrisPlan.build(new MemoryPack("ArchitectureOneFlipOff", writes), null);
        IrisPlan.Step configuredStep = oneFlipOff.steps(ProgramSet.Stage.COMPOSITE).get(0);
        check("flip properties disable only the named buffer", !configuredStep.inPlace()[0] && configuredStep.inPlace()[1]);
    }

    private static void legacyGlslRewrite() throws PackException {
        String source = "#version 120\nvarying vec2 uv;\nvoid main() { gl_FragColor = texture2D(image, uv); }\n";
        String translated = LegacyGlsl.translate(source, false, LegacyGlsl.Kind.FULLSCREEN);
        check("legacy GLSL version is upgraded", translated.contains("#version 330 core"));
        check("fragment varyings and color output are rewritten", translated.contains("in vec2 uv;") && translated.contains("out vec4 outColor0;"));
        check("legacy texture calls are rewritten", translated.contains("texture(image, uv)") && !translated.contains("texture2D("));
    }

    private static void translationCacheInvalidation() throws IOException {
        Path folder = Files.createTempDirectory("metallum-extra-cache-check-");
        try {
            TranslationCache cache = new TranslationCache(folder);
            ByteBuffer shader = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
            String key = TranslationCache.key("compiler-v1", shader, 0, Map.of("Position", "RGB32_FLOAT"), false);
            String changedShader = TranslationCache.key("compiler-v1", ByteBuffer.wrap(new byte[] {1, 2, 3, 5}), 0,
                    Map.of("Position", "RGB32_FLOAT"), false);
            String changedSalt = TranslationCache.key("compiler-v2", shader, 0, Map.of("Position", "RGB32_FLOAT"), false);

            check("cache key changes when shader bytes change", !key.equals(changedShader));
            check("cache key changes when compiler/version salt changes", !key.equals(changedSalt));
            cache.store(key, new TranslationCache.Msl("fragment float4 main0() { return 0; }\n", false, Set.of()));
            check("same key reads a persisted translation", cache.lookup(key) != null);
            check("changed shader starts as a cache miss", cache.lookup(changedShader) == null);
            check("changed salt starts as a cache miss", cache.lookup(changedSalt) == null);
        } finally {
            try (var paths = Files.walk(folder)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static final String VERTEX = "#version 330\nout vec2 texcoord;\nvoid main() { gl_Position = vec4(0.0); texcoord = vec2(0.0); }\n";

    private static String fragment() {
        return "#version 330\nin vec2 texcoord;\n/* RENDERTARGETS: 0 */\nlayout(location = 0) out vec4 color;\nvoid main() { color = vec4(1.0); }\n";
    }

    private static String finalFragment() {
        return "#version 330\nuniform sampler2D colortex0;\nin vec2 texcoord;\nout vec4 color;\nvoid main() { color = texture(colortex0, texcoord); }\n";
    }

    private static void check(final String description, final boolean condition) {
        checks++;
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS: " + description);
    }

    private record MemoryPack(String name, Map<String, String> contents) implements ShaderPack {
        @Override
        public String read(final String path) {
            return contents.get(path);
        }

        @Override
        public Set<String> files() {
            return contents.keySet();
        }
    }
}
