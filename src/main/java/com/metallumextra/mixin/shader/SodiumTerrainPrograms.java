package com.metallumextra.mixin.shader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Shaders: Sodium's pipelines for its layers of terrain, kept in a static map, which a standard pack's programs make out of date. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public interface SodiumTerrainPrograms {
    @Accessor("programs")
    static Map<?, ?> metallumExtra$programs() {
        throw new AssertionError();
    }
}
