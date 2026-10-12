package com.metallumextra.mixin.voxy;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Voxy keeps its two model-data buffers package-private, but the Metal renderer needs their ids:
 * under the Stage-2 shim every GlBuffer id names a CPU allocation in
 * {@code VoxyGlRegistry}, so the accessor turns "the baked model structs" and "the model/biome
 * colour LUT" into plain addresses the capture step can read when it resolves each quad's tint.
 */
@Mixin(value = ModelStore.class, remap = false)
public interface ModelStoreBufferAccessor {
    @Accessor("modelBuffer")
    GlBuffer metallumExtra$modelBuffer();

    @Accessor("modelColourBuffer")
    GlBuffer metallumExtra$modelColourBuffer();
}
