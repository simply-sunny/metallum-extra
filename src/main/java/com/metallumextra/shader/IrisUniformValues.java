package com.metallumextra.shader;

import com.metallumextra.shader.pack.IrisUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The values of the standard Iris uniforms for one frame (see {@link IrisUniforms}), worked out from what the game knows.
 * <p>
 * This is arithmetic only, so it can be tested without a game: {@link #frame} takes everything it needs in {@link Inputs}.
 * The conventions are Iris's, not the game's: {@code gbufferProjection} is an ordinary OpenGL projection with the near
 * plane at {@value #NEAR} and the far plane at the render distance, whatever the game itself draws with, and depth
 * textures hold OpenGL depth to match (see {@link #glDepth} and {@code internal/depth.fsh}).
 */
public final class IrisUniformValues {
    public static final float NEAR = 0.05F;

    /** Everything one frame's values are made from. Times are in seconds, angles in radians, positions in blocks. */
    public record Inputs(Matrix4f view, Matrix4f gameProjection, float far, double cameraX, double cameraY, double cameraZ,
                         float sunAngle, float moonAngle, float sunPathTilt, long clockTicks, int moonPhase,
                         float rain, float thunder, double time, double frameTime, long frameCounter, int width, int height,
                         float screenBrightness, float nightVision, float blindness, float darkness,
                         float fogRed, float fogGreen, float fogBlue, float skyRed, float skyGreen, float skyBlue,
                         int eyeBlockLight, int eyeSkyLight, int eyeInWater) {
    }

    /** What carries over from frame to frame: the previous frame's matrices and position, and the smoothed values. */
    private final Matrix4f previousView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private double previousX;
    private double previousY;
    private double previousZ;
    private boolean started;
    private double wetness;
    private double smoothBlock;
    private double smoothSky;

    /** The projection Iris shaders expect: the game's field of view and aspect, with near {@value #NEAR} and far {@code far}, in OpenGL's depth range. */
    public static Matrix4f glProjection(final Matrix4f game, final float far) {
        Matrix4f result = new Matrix4f(game);
        result.m22((far + NEAR) / (NEAR - far));
        result.m32(2.0F * far * NEAR / (NEAR - far));
        result.m23(-1.0F);
        result.m33(0.0F);
        return result;
    }

    /**
     * OpenGL window depth (0 at the near plane, 1 at the far plane) of a point the game drew with depth {@code gameDepth}.
     * The game's depth is {@code A + B / distance} with A and B the third and fourth entries of its projection's third
     * column, so the distance from the camera follows from it.
     */
    public static double glDepth(final double gameDepth, final Matrix4f game, final float far) {
        double a = game.m22();
        double b = game.m32();
        double distance = b / (gameDepth + a);
        return 0.5 + 0.5 * (((double) far + NEAR) / (far - NEAR) - 2.0 * far * NEAR / ((far - NEAR) * distance));
    }

    /** Iris's {@code sunAngle}: 0 at sunrise, 0.25 at noon, 0.5 at sunset, 0.75 at midnight. The game's angle is 0 at noon. */
    public static float irisSunAngle(final float gameAngle) {
        float turns = gameAngle / (2.0F * (float) Math.PI);
        float angle = turns + 0.25F;
        return angle - (float) Math.floor(angle);
    }

    /** The direction to something on the sun's or moon's path, in the game's camera-relative axes. */
    public static Vector3f celestialDirection(final float angle, final float tilt) {
        return new Vector3f(0, 1, 0).rotateX(angle).rotateY(-(float) Math.PI / 2.0F).rotateX(tilt);
    }

    /** The values of one frame, by name; matrices are 16 numbers in column-major order, vectors their components. */
    public Map<String, double[]> frame(final Inputs in) {
        Map<String, double[]> v = new HashMap<>();
        Matrix4f projection = glProjection(in.gameProjection, in.far);
        Matrix4f viewInverse = new Matrix4f(in.view).invert();
        Matrix4f projectionInverse = new Matrix4f(projection).invert();
        if (!started) {
            previousView.set(in.view);
            previousProjection.set(projection);
            previousX = in.cameraX;
            previousY = in.cameraY;
            previousZ = in.cameraZ;
            wetness = in.rain;
            smoothBlock = in.eyeBlockLight * 16.0;
            smoothSky = in.eyeSkyLight * 16.0;
            started = true;
        }
        put(v, "gbufferModelView", in.view);
        put(v, "gbufferModelViewInverse", viewInverse);
        put(v, "gbufferProjection", projection);
        put(v, "gbufferProjectionInverse", projectionInverse);
        put(v, "gbufferPreviousModelView", previousView);
        put(v, "gbufferPreviousProjection", previousProjection);
        // For the programs that draw the world: terrain has no model matrix of its own (chunk offsets are added to positions), and the
        // game's own projection, not the OpenGL one, is what its depth buffer was drawn with.
        put(v, "modelViewMatrix", in.view);
        put(v, "modelViewMatrixInverse", viewInverse);
        put(v, "projectionMatrix", in.gameProjection);
        put(v, "projectionMatrixInverse", new Matrix4f(in.gameProjection).invert());
        org.joml.Matrix3f normal = new org.joml.Matrix3f(in.view).invert().transpose();
        float[] nine = new float[9];
        normal.get(nine);
        double[] normalValues = new double[9];
        for (int i = 0; i < 9; i++) normalValues[i] = nine[i];
        v.put("normalMatrix", normalValues);
        v.put("cameraPosition", new double[] {in.cameraX, in.cameraY, in.cameraZ});
        v.put("previousCameraPosition", new double[] {previousX, previousY, previousZ});

        float sunAngle = irisSunAngle(in.sunAngle);
        Vector3f sun = viewDirection(in.view, celestialDirection(in.sunAngle, in.sunPathTilt));
        Vector3f moon = viewDirection(in.view, celestialDirection(in.moonAngle, in.sunPathTilt));
        boolean day = sunAngle <= 0.5F;
        v.put("sunPosition", vec(sun));
        v.put("moonPosition", vec(moon));
        v.put("shadowLightPosition", vec(day ? sun : moon));
        v.put("upPosition", vec(viewDirection(in.view, new Vector3f(0, 1, 0))));
        v.put("sunAngle", new double[] {sunAngle});
        v.put("shadowAngle", new double[] {day ? sunAngle : sunAngle - 0.5F});

        // Smoothed over time by their half-lives, in game ticks: how long until half the distance to the target is covered.
        double ticks = in.frameTime * 20.0;
        wetness += (in.rain - wetness) * (1.0 - Math.pow(0.5, ticks / 600.0));
        double blend = 1.0 - Math.pow(0.5, ticks / 10.0);
        smoothBlock += (in.eyeBlockLight * 16.0 - smoothBlock) * blend;
        smoothSky += (in.eyeSkyLight * 16.0 - smoothSky) * blend;
        v.put("rainStrength", new double[] {in.rain});
        v.put("wetness", new double[] {wetness});
        v.put("thunderStrength", new double[] {in.thunder});
        v.put("eyeBrightness", new double[] {in.eyeBlockLight * 16, in.eyeSkyLight * 16});
        v.put("eyeBrightnessSmooth", new double[] {Math.round(smoothBlock), Math.round(smoothSky)});
        v.put("eyeAltitude", new double[] {in.cameraY});

        v.put("frameTime", new double[] {in.frameTime});
        v.put("frameTimeCounter", new double[] {in.time % 3600.0});
        v.put("frameCounter", new double[] {in.frameCounter % 720720});
        v.put("viewWidth", new double[] {in.width});
        v.put("viewHeight", new double[] {in.height});
        v.put("aspectRatio", new double[] {(double) in.width / in.height});
        v.put("near", new double[] {NEAR});
        v.put("far", new double[] {in.far});
        v.put("screenBrightness", new double[] {in.screenBrightness});
        v.put("nightVision", new double[] {in.nightVision});
        v.put("blindness", new double[] {in.blindness});
        v.put("darknessFactor", new double[] {in.darkness});
        v.put("worldTime", new double[] {Math.floorMod(in.clockTicks, 24000L)});
        v.put("worldDay", new double[] {Math.floorDiv(in.clockTicks, 24000L)});
        v.put("moonPhase", new double[] {in.moonPhase});
        v.put("isEyeInWater", new double[] {in.eyeInWater});
        v.put("fogColor", new double[] {in.fogRed, in.fogGreen, in.fogBlue});
        v.put("skyColor", new double[] {in.skyRed, in.skyGreen, in.skyBlue});

        previousView.set(in.view);
        previousProjection.set(projection);
        previousX = in.cameraX;
        previousY = in.cameraY;
        previousZ = in.cameraZ;
        return v;
    }

    /** A direction as the shaders see a position of the sun or moon: in view space, 100 blocks long. */
    private static Vector3f viewDirection(final Matrix4f view, final Vector3f world) {
        return view.transformDirection(new Vector3f(world).normalize().mul(100.0F));
    }

    private static double[] vec(final Vector3f v) {
        return new double[] {v.x, v.y, v.z};
    }

    private static void put(final Map<String, double[]> values, final String name, final Matrix4f matrix) {
        double[] out = new double[16];
        float[] floats = new float[16];
        matrix.get(floats);
        for (int i = 0; i < 16; i++) out[i] = floats[i];
        values.put(name, out);
    }

    /** Writes the members of a program's block into {@code out} (which must be at least the block's size) as {@code std140} lays them out. */
    public static void write(final IrisUniforms.Layout layout, final Map<String, double[]> values, final ByteBuffer out) {
        for (Map.Entry<String, Integer> member : layout.offsets().entrySet()) {
            IrisUniforms.Uniform uniform = IrisUniforms.find(member.getKey());
            double[] value = values.get(member.getKey());
            if (value == null) continue;
            int at = member.getValue();
            if (uniform.type().equals("mat3")) {
                // Three columns, each padded to four floats.
                for (int column = 0; column < 3; column++) {
                    for (int row = 0; row < 3; row++) out.putFloat(at + 16 * column + 4 * row, (float) value[column * 3 + row]);
                }
                continue;
            }
            boolean integer = uniform.type().startsWith("int") || uniform.type().startsWith("ivec");
            for (int i = 0; i < value.length; i++) {
                if (integer) out.putInt(at + 4 * i, (int) Math.round(value[i]));
                else out.putFloat(at + 4 * i, (float) value[i]);
            }
        }
    }

    /** The names of the members, for tests and the debug log. */
    public static List<String> names() {
        return IrisUniforms.ALL.stream().map(IrisUniforms.Uniform::name).toList();
    }
}
