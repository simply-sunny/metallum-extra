package com.metallumextra.shader;

import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * The game's renderer, as far as the held item goes: a shader pack's deferred programs light the item with the rest of the world, so it has to be drawn
 * before them, where the game draws it last (see {@link Shaders#beforeTranslucentTerrain}).
 */
public interface HandSource {
    /** Draws the held item and arm the way the game does at the end of the frame, with the world's own depth left alone; the depth they have goes to {@link #metallumExtra$handDepth}. */
    void metallumExtra$drawHandNow();

    /** The depth buffer the item and arm were drawn with (cleared to 0, and not 0 where they are). */
    GpuTextureView metallumExtra$handDepth();
}
