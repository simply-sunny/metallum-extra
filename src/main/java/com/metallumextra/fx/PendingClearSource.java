package com.metallumextra.fx;

import com.mojang.renderpearl.api.textures.GpuTexture;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/** Metallum's command encoder (made to implement this by a mixin): which colour a texture is waiting to be cleared to. */
public interface PendingClearSource {
    @Nullable Vector4fc metallumExtra$pendingClear(GpuTexture texture);
}
