package com.metallumextra.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The lighting model of the built-in shader pack, as worked out once per frame (see {@link Shaders}): where the sun and moon are and how they
 * light the world, the color of the sky, the fog. They reach a pack's programs as the {@code Mx} uniforms, which are not Iris's (see
 * {@link IrisPipeline} and {@code pack/shaders/lib/uniforms.glsl}); a pack that reads them runs only in this mod.
 */
public final class ShaderGlobals {
    /** Camera-relative world space to view space (rotation only), and the projection the world is drawn with. */
    public final Matrix4f view = new Matrix4f();
    public final Matrix4f viewInverse = new Matrix4f();
    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f projectionInverse = new Matrix4f();
    /** xyz: direction to the light that casts shadows (sun or moon). w: shadow strength, 0 when there is no shadow map. */
    public final Vector4f lightDir = new Vector4f(0, 1, 0, 0);
    /** rgb: linear color of that light. w: 1 by day, 0 by night. */
    public final Vector4f lightColor = new Vector4f();
    /** xyz: direction to the sun. w: how much of it is above the horizon. */
    public final Vector4f sunDir = new Vector4f(0, 1, 0, 0);
    /** rgb: linear light from the open sky. w: rain strength. */
    public final Vector4f skyAmbient = new Vector4f();
    /** rgb: linear color of block light at full strength. w: torch flicker. */
    public final Vector4f blockLight = new Vector4f();
    /** rgb: light that reaches everywhere (dimension ambient, night vision). w: darkness effect. */
    public final Vector4f minAmbient = new Vector4f();
    /** rgb: linear sky color overhead. w: star brightness. */
    public final Vector4f skyZenith = new Vector4f();
    /** rgb: linear sky color at the horizon. w: 0 = fog fades to the sky, 1 = fog fades to the game's fog color. */
    public final Vector4f skyHorizon = new Vector4f();
    /** rgb: linear sunrise or sunset tint. a: its strength. */
    public final Vector4f sunsetColor = new Vector4f();
    /** xyz: direction to the moon. w: how much of it is above the horizon. */
    public final Vector4f moonDir = new Vector4f(0, -1, 0, 0);
    /** xyz: where the light-color grid starts, camera-relative. w: the size of one of its cells in blocks. */
    public final Vector4f lightGrid = new Vector4f(0, 0, 0, 1);
    /** Sky light where the camera is, 0 to 1, smoothed over time. */
    public float eyeSky;
    /** x, y: where the fog of the surroundings (rain, water, lava) starts and ends. z, w: the same for the fog at the edge of the loaded world. */
    public final Vector4f fog = new Vector4f();
    /** x: where the sky's fog ends. y: where the clouds' does. */
    public final Vector4f fogEnds = new Vector4f();
    /** The fog's color and how strongly it is applied (alpha). */
    public final Vector4f fogColor = new Vector4f(1, 1, 1, 1);

    static void setLinear(final Vector4f target, final float red, final float green, final float blue, final float scale) {
        target.x = (float) Math.pow(Math.max(red, 0.0), 2.2) * scale;
        target.y = (float) Math.pow(Math.max(green, 0.0), 2.2) * scale;
        target.z = (float) Math.pow(Math.max(blue, 0.0), 2.2) * scale;
    }

    static void setLinear(final Vector4f target, final Vector3f srgb, final float scale) {
        setLinear(target, srgb.x, srgb.y, srgb.z, scale);
    }
}
