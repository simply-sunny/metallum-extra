package com.metallumextra;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.util.Arrays;

/**
 * Shaders with many textures. Metallum 0.0.23 numbers a pipeline's uniform blocks and textures in one sequence
 * and gives each texture's sampler the same number as the texture. Metal has room for well over 16 textures per
 * shader stage but only 16 samplers (0-15), so a shader whose textures are numbered 16 or higher does not compile.
 * <p>
 * For a shader stage where that would happen, the samplers are renumbered 0, 1, 2... in texture order. A stage
 * that already fits is left exactly as Metallum made it. The same rule is applied in two places that must agree:
 * {@link #compact} when the shader is converted to Metal, and {@link #table} when its samplers are bound.
 */
public final class SamplerSlots {
    /** Metal's sampler slots per shader stage. */
    public static final int METAL_SAMPLER_SLOTS = 16;
    /** Same values as MetalCompiledRenderPipeline.STAGE_VERTEX / STAGE_FRAGMENT. */
    public static final int STAGE_VERTEX = 1;
    public static final int STAGE_FRAGMENT = 2;

    private static final int SPV_EXECUTION_MODEL_VERTEX = 0;
    private static final int SPV_DECORATION_BINDING = 33;
    private static final int SPV_DECORATION_DESCRIPTOR_SET = 34;
    private static final int SPV_DIM_BUFFER = 5;

    private static volatile boolean logged;

    /** Which sampler state each texture of a pipeline is bound with, when the pipeline has more textures than Metal has samplers (see {@link #assign}). */
    public interface Kinds {
        /** The kind of sampler each of the pipeline's textures is bound with (its name to a small number: the same number, the same sampler state); null when not known. */
        java.util.@Nullable Map<String, Integer> kinds(String pipeline);
    }

    private static volatile Kinds kindSource = pipeline -> null;
    /** The pipeline whose shaders are being converted on this thread. */
    private static final ThreadLocal<String> PIPELINE = new ThreadLocal<>();

    /** The Metal slot Metallum gave each binding of the shaders being converted on this thread (its {@code MetalIndices.byBinding}); null when not known. */
    private static final ThreadLocal<int[]> METAL_SLOTS = new ThreadLocal<>();

    public static void metalSlots(final int @Nullable [] byBinding) {
        METAL_SLOTS.set(byBinding);
    }

    public static void kinds(final Kinds source) {
        kindSource = source;
    }

    /** Called when the conversion of a pipeline's shaders starts on this thread. */
    public static void converting(final String pipeline) {
        PIPELINE.set(pipeline);
    }

    private static final java.util.regex.Pattern SAMPLER_PARAMETER = java.util.regex.Pattern.compile(",\\s*sampler\\s+(\\w+)\\s*\\[\\[sampler\\((\\d+)\\)\\]\\]");

    /**
     * The Metal shader of a pipeline whose textures share samplers: Metal wants every sampler of a function at a slot of its own, so the samplers that
     * {@link #assign} put in one slot are one parameter, and what read the others reads that one.
     */
    public static String shareSamplers(final String metal) {
        // Only the shader's entry function has its samplers placed in slots (the functions it calls take theirs as ordinary parameters, which must stay apart).
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile("(?m)^(?:fragment|vertex|kernel)\\s[^\\n]*\\bmain0\\(").matcher(metal);
        if (!entry.find()) return metal;
        String head = metal.substring(0, entry.start());
        String body = metal.substring(entry.start());
        java.util.regex.Matcher match = SAMPLER_PARAMETER.matcher(body);
        java.util.Map<String, String> kept = new java.util.HashMap<>();
        java.util.Map<String, String> renames = new java.util.LinkedHashMap<>();
        StringBuilder out = new StringBuilder(body.length());
        while (match.find()) {
            String first = kept.putIfAbsent(match.group(2), match.group(1));
            if (first == null) {
                match.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(match.group()));
            } else {
                renames.put(match.group(1), first);
                match.appendReplacement(out, "");
            }
        }
        if (renames.isEmpty()) return metal;
        match.appendTail(out);
        String result = out.toString();
        for (java.util.Map.Entry<String, String> rename : renames.entrySet()) {
            result = result.replaceAll("\\b" + java.util.regex.Pattern.quote(rename.getKey()) + "\\b", rename.getValue());
        }
        return head + result;
    }

    /** Whether the shaders being converted on this thread are of a pipeline whose textures may share samplers. */
    public static boolean sharing() {
        String pipeline = PIPELINE.get();
        return pipeline != null && kindSource.kinds(pipeline) != null;
    }

    /** What the conversion of the shaders on this thread depends on besides the shaders: empty for nearly every pipeline. */
    public static String fingerprint() {
        String pipeline = PIPELINE.get();
        java.util.Map<String, Integer> kinds = pipeline == null ? null : kindSource.kinds(pipeline);
        return kinds == null ? "" : new java.util.TreeMap<>(kinds).toString();
    }

    private SamplerSlots() {
    }

    /** Added to MetalCompiledRenderPipeline. */
    public interface Pipeline {
        /** Sampler slot for texture {@code binding} at {@code [binding * 2]} (vertex) and {@code [binding * 2 + 1]} (fragment); null when every sampler keeps its texture's number. */
        byte @Nullable [] metallumExtra$samplerSlots();
    }

    /**
     * The renumbering for one stage: {@code slots[i]} goes with {@code bindings[i]}. Returns null when the stage
     * already fits, and also when it has more than 16 textures (nothing can make that fit).
     */
    public static int @Nullable [] renumber(final int[] sortedBindings) {
        int count = sortedBindings.length;
        if (count == 0 || sortedBindings[count - 1] < METAL_SAMPLER_SLOTS || count > METAL_SAMPLER_SLOTS) return null;
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) slots[i] = i;
        return slots;
    }

    /** Called on a SPIRV-Cross compiler just before it writes the Metal shader for one stage. */
    public static void compact(final long compiler) {
        if (!ExtraConfig.get().manyTextures) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer active = stack.mallocPointer(1);
            PointerBuffer resources = stack.mallocPointer(1);
            PointerBuffer list = stack.mallocPointer(1);
            PointerBuffer size = stack.mallocPointer(1);
            if (Spvc.spvc_compiler_get_active_interface_variables(compiler, active) != Spvc.SPVC_SUCCESS
                    || Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, resources, active.get(0)) != Spvc.SPVC_SUCCESS
                    || Spvc.spvc_resources_get_resource_list_for_type(resources.get(0), Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, list, size) != Spvc.SPVC_SUCCESS) {
                return;
            }
            int total = (int) size.get(0);
            if (total == 0) return;

            // Texel buffers are listed as sampled images too, but they are bound without a sampler.
            SpvcReflectedResource.Buffer images = SpvcReflectedResource.create(list.get(0), total);
            int[] bindings = new int[total];
            int[] sets = new int[total];
            String[] names = new String[total];
            int count = 0;
            for (int i = 0; i < total; i++) {
                SpvcReflectedResource image = images.get(i);
                if (Spvc.spvc_type_get_image_dimension(Spvc.spvc_compiler_get_type_handle(compiler, image.type_id())) == SPV_DIM_BUFFER) continue;
                bindings[count] = Spvc.spvc_compiler_get_decoration(compiler, image.id(), SPV_DECORATION_BINDING);
                sets[count] = Spvc.spvc_compiler_get_decoration(compiler, image.id(), SPV_DECORATION_DESCRIPTOR_SET);
                names[count] = image.nameString();
                count++;
            }
            int[] sorted = Arrays.copyOf(bindings, count);
            Arrays.sort(sorted);
            String[] sortedNames = new String[count];
            for (int i = 0; i < count; i++) sortedNames[Arrays.binarySearch(sorted, bindings[i])] = names[i];
            String pipeline = PIPELINE.get();
            int[] slots = assign(sorted, sortedNames, pipeline == null ? null : kindSource.kinds(pipeline));
            if (slots == null) {
                if (count > METAL_SAMPLER_SLOTS) {
                    MetallumExtra.LOGGER.warn("[Metallum Extra] A shader uses {} textures in one stage; Metal allows {} samplers, so it will not compile", count, METAL_SAMPLER_SLOTS);
                }
                return;
            }

            int stage = Spvc.spvc_compiler_get_execution_model(compiler);
            SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
            for (int i = 0; i < count; i++) {
                Spvc.spvc_msl_resource_binding_init(binding);
                // The texture keeps the slot Metallum gave it (what binds it at run time goes by that); only the sampler is moved.
                int[] metal = METAL_SLOTS.get();
                int own = metal != null && bindings[i] >= 0 && bindings[i] < metal.length ? metal[bindings[i]] : bindings[i];
                binding.stage(stage).desc_set(sets[i]).binding(bindings[i])
                        .msl_buffer(own).msl_texture(own).msl_sampler(slots[Arrays.binarySearch(sorted, bindings[i])]);
                Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding);
            }
            if (!logged) {
                logged = true;
                MetallumExtra.LOGGER.info("[Metallum Extra] Sampler renumbering in use: first is a {} shader with {} textures, highest in slot {}",
                        stage == SPV_EXECUTION_MODEL_VERTEX ? "vertex" : "fragment", count, sorted[count - 1]);
            }
        }
    }

    /**
     * The bind-time side of {@link #compact}, from the textures each stage of a pipeline uses.
     * Both arrays must be sorted ascending.
     */
    public static byte @Nullable [] table(final int[] vertexBindings, final String[] vertexNames, final int[] fragmentBindings, final String[] fragmentNames, final String pipeline) {
        if (!ExtraConfig.get().manyTextures) return null;
        java.util.Map<String, Integer> kinds = kindSource.kinds(pipeline);
        int[] vertexSlots = assign(vertexBindings, vertexNames, kinds);
        int[] fragmentSlots = assign(fragmentBindings, fragmentNames, kinds);
        if (vertexSlots == null && fragmentSlots == null) return null;

        int highest = Math.max(vertexBindings.length == 0 ? 0 : vertexBindings[vertexBindings.length - 1],
                fragmentBindings.length == 0 ? 0 : fragmentBindings[fragmentBindings.length - 1]);
        byte[] table = new byte[(highest + 1) * 2];
        for (int binding = 0; binding <= highest; binding++) {
            // Clamped so a texture in a stage with too many of them cannot index outside Metal's sampler table.
            table[binding * 2] = table[binding * 2 + 1] = (byte) Math.min(binding, METAL_SAMPLER_SLOTS - 1);
        }
        fill(table, vertexBindings, vertexSlots, 0);
        fill(table, fragmentBindings, fragmentSlots, 1);
        return table;
    }

    /**
     * The sampler slot of each texture of a stage (the textures' binding numbers sorted ascending, and their names). A stage that fits is left as it is unless a
     * texture is numbered past 15, which are then numbered from 0. A stage with more textures than there are samplers shares them: textures that are bound with
     * the same kind of sampler (the same filtering and wrapping) use the same slot, which is possible when the kinds are known (see {@link Kinds}).
     */
    static int @Nullable [] assign(final int[] sortedBindings, final String[] names, final java.util.@Nullable Map<String, Integer> kinds) {
        int count = sortedBindings.length;
        if (count <= METAL_SAMPLER_SLOTS) return renumber(sortedBindings);
        if (kinds == null) return null;
        java.util.Map<Integer, Integer> dense = new java.util.HashMap<>();
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            Integer kind = kinds.get(names[i]);
            // A texture of unknown kind keeps a slot to itself.
            int key = kind != null ? kind : 1000 + i;
            slots[i] = dense.computeIfAbsent(key, k -> dense.size());
        }
        return dense.size() > METAL_SAMPLER_SLOTS ? null : slots;
    }

    private static void fill(final byte[] table, final int[] bindings, final int @Nullable [] slots, final int stageOffset) {
        if (slots == null) return;
        for (int i = 0; i < bindings.length; i++) {
            table[bindings[i] * 2 + stageOffset] = (byte) slots[i];
        }
    }
}
