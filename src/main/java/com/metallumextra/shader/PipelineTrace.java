package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderPipelines;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Trace of the vertex formats and pipeline fits, for finding what a pass and a pipeline disagree about before the game does. Off unless
 * {@code -Dmetallumextra.trace=true}; then it writes into the game's {@code metallum-extra/trace} folder:
 * <ul>
 * <li>{@code catalogue.txt}: every pipeline the game defines ({@link RenderPipelines}), with its shaders, vertex formats, targets and layouts;</li>
 * <li>{@code fit.log}: every pipeline set on a render pass, with the pass's colour attachments and whether they match the pipeline's targets;</li>
 * <li>{@code variants.log}: every pipeline the pack's translation made from one of the game's, with the targets of each.</li>
 * </ul>
 * Each line is written once per launch, however often it happens.
 */
public final class PipelineTrace {
    public static final boolean ON = Boolean.getBoolean("metallumextra.trace");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static Path folder;
    private static boolean catalogued;

    private PipelineTrace() {
    }

    private static synchronized Path folder() {
        if (folder == null) {
            folder = FabricLoader.getInstance().getGameDir().resolve("metallum-extra").resolve("trace");
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                MetallumExtra.LOGGER.error("[Metallum Extra] The trace folder {} cannot be made: {}", folder, e.getMessage());
            }
        }
        return folder;
    }

    private static void write(final String file, final String line) {
        if (!SEEN.add(file + "|" + line)) return;
        try {
            Files.writeString(folder().resolve(file), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] Could not write the trace {}: {}", file, e.getMessage());
        }
    }

    /** The catalogue of every pipeline the game defines, written once. */
    public static synchronized void catalogue() {
        if (!ON || catalogued) return;
        catalogued = true;
        StringBuilder out = new StringBuilder();
        List<Field> fields = List.of(RenderPipelines.class.getFields());
        for (Field field : fields) {
            if (!Modifier.isStatic(field.getModifiers()) || !RenderPipeline.class.isAssignableFrom(field.getType())) continue;
            try {
                RenderPipeline pipeline = (RenderPipeline) field.get(null);
                out.append("== ").append(field.getName()).append(" location=").append(pipeline.getLocation()).append('\n');
                out.append("   topology=").append(pipeline.getPrimitiveTopology()).append(" polygon=").append(pipeline.getPolygonMode())
                        .append(" cull=").append(pipeline.isCull()).append(" depthTexture=").append(pipeline.wantsDepthTexture()).append('\n');
                out.append("   shaders=").append(pipeline.getShaders()).append('\n');
                out.append("   defines=").append(pipeline.getShaderDefines()).append('\n');
                for (var vertex : pipeline.getVertexFormatBindings()) {
                    out.append("   vertex size=").append(vertex.getVertexSize()).append(" step=").append(vertex.getStepRate()).append(" elements=")
                            .append(vertex.getElements().stream().map(e -> e.name() + "@" + e.offset() + ":" + e.format()).collect(Collectors.joining(", "))).append('\n');
                }
                for (var target : pipeline.getColorTargetStates()) out.append("   target ").append(target).append('\n');
                out.append("   depth ").append(pipeline.getDepthStencilState()).append('\n');
                for (var layout : pipeline.getBindGroupLayouts()) out.append("   layout ").append(layout).append('\n');
            } catch (IllegalAccessException | RuntimeException e) {
                out.append("== ").append(field.getName()).append(" could not be read: ").append(e).append('\n');
            }
        }
        try {
            Files.writeString(folder().resolve("catalogue.txt"), out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] Could not write the pipeline catalogue: {}", e.getMessage());
        }
        MetallumExtra.LOGGER.info("[Metallum Extra] Wrote the pipeline catalogue of {} fields to {}", fields.size(), folder());
    }

    private static String formats(final List<?> attachments) {
        return attachments.stream().map(a -> String.valueOf(((RenderPassDescriptor.Attachment<?>) a).textureView().texture().getFormat()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** A pipeline set on a pass: {@code origin} is the game's, {@code used} the one drawn with (the same unless the pack replaced it). */
    public static void pass(final RenderPipeline origin, final RenderPipeline used, final List<?> attachments, final boolean depth) {
        if (!ON) return;
        String verdict = used.getColorTargetStates().size() == attachments.size() ? "OK" : "MISMATCH";
        String line = verdict + " pass=" + formats(attachments) + (depth ? " depth" : "") + " phase=" + Shaders.phase() + " pack=" + IrisPipeline.inUse()
                + " origin=" + origin.getLocation() + " targets=" + origin.getColorTargetStates().size() + " used=" + used.getLocation()
                + " usedTargets=" + used.getColorTargetStates().size();
        write("fit.log", line);
    }

    /** A pipeline set on a pass that is not one of the game's (the compiled pipeline could not be traced back). */
    public static void unknown(final Object pipeline, final List<?> attachments, final boolean depth) {
        if (!ON) return;
        write("fit.log", "UNKNOWN pass=" + formats(attachments) + (depth ? " depth" : "") + " pipeline=" + pipeline);
    }

    /** A pack's copy of a pipeline that failed where the game's would have drawn. */
    public static void failed(final RenderPipeline origin, final RenderPipeline used, final Throwable error) {
        if (!ON) return;
        write("fit.log", "FAILED origin=" + origin.getLocation() + " used=" + used.getLocation() + " error=" + error);
    }

    /** A pipeline the translation made from {@code original}, with what it draws with. */
    public static void variant(final RenderPipeline original, final RenderPipeline made, final String program) {
        if (!ON) return;
        write("variants.log", "variant of " + original.getLocation() + " -> " + made.getLocation() + " program=" + program + " targets="
                + original.getColorTargetStates().size() + "->" + made.getColorTargetStates().size() + " vertex=" + made.getVertexFormatBindings());
    }
}
