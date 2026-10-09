package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The game's own pipelines (entities, items, particles ...) and the programs of a standard pack that draw them. */
public final class IrisWorld {
    private static final boolean LOG = Boolean.getBoolean("metallumextra.logPipelines");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private IrisWorld() {
    }

    /** The id a pipeline's shader is compiled from: its own, unless a standard pack has a program for the pipeline. */
    public static Identifier shaderId(final RenderPipeline pipeline, final Identifier original) {
        if (LOG && LOGGED.add(pipeline.getLocation() + "|" + original)) {
            StringBuilder format = new StringBuilder();
            for (var binding : pipeline.getVertexFormatBindings()) {
                if (binding != null) for (var element : binding.getElements()) format.append(element.name()).append(':').append(element.format().name()).append(' ');
            }
            var targets = pipeline.getColorTargetStates();
            MetallumExtra.LOGGER.info("[Metallum Extra] pipeline {} shader {} format {} targets {} defines {}", pipeline.getLocation(), original, format.toString().strip(),
                    targets.length > 0 && targets[0] != null ? targets[0].format() : "none", pipeline.getShaderDefines());
        }
        return original;
    }
}
