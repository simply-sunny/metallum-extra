package com.metallumextra.shader;

import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.sodium.ShadowTerrain;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The shadow map: the world drawn once more from the sun's (or moon's) point of view, keeping only depth.
 * <p>
 * The terrain in it hardly changes from one frame to the next, and drawing it is most of what shadows cost, so it is
 * drawn into a map of its own only when it has gone stale, and copied from there on every other frame. Mobs, items and
 * the player move, so they are drawn on top of the copy each frame. The cached terrain is stale when:
 * <ul>
 * <li>the light has turned more than {@link #LIGHT_TOLERANCE_DEGREES},</li>
 * <li>the player is more than {@link #MARGIN} blocks from where the map was centered (the map is that much larger than
 * the shadow distance, so shadows reach the shadow distance wherever the player is within that margin),</li>
 * <li>Sodium has changed a section's mesh (a block was placed, a chunk loaded), or</li>
 * <li>the settings, the pack or the map's size changed.</li>
 * </ul>
 * Between refreshes the map keeps the matrix and the fish-eye center it was drawn with, and what is read from it is
 * moved by how far the player has walked since; the shaders see only a matrix, as before.
 */
public final class ShadowPass {
    /** How far the player may walk from the map's center before the terrain is drawn again, in blocks. */
    private static final float MARGIN = 8.0F;
    /** How far the light may turn before the terrain is drawn again. A shadow's far end moves about a block per degree per 50 blocks. */
    private static final double LIGHT_TOLERANCE_DEGREES = 0.2;
    private static final float LIGHT_TOLERANCE_DOT = (float) Math.cos(Math.toRadians(LIGHT_TOLERANCE_DEGREES));
    /** Development aid: draw the terrain every frame, as without the cache, to compare. */
    private static final boolean NO_CACHE = Boolean.getBoolean("metallumextra.noShadowCache");

    private static final Vector4f CLEAR_COLOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    /** The sun and moon travel east to west, so north is always at right angles to their light. */
    private static final Vector3f NORTH = new Vector3f(0.0F, 0.0F, -1.0F);

    /** What the cached terrain was drawn with. */
    private static final Matrix4f LIGHT_VIEW = new Matrix4f();
    private static final Vector3f TO_LIGHT = new Vector3f();
    private static Vec3 camera = Vec3.ZERO;
    private static float range;
    private static float depth;

    /** What a standard pack's shadow programs see: the light's view with the player's walk since the terrain was drawn, and the orthographic projection of the map. */
    private static final Matrix4f SHADOW_VIEW = new Matrix4f();
    private static final Matrix4f SHADOW_PROJECTION = new Matrix4f();

    private static final Vector3f NOW_TO_LIGHT = new Vector3f();
    private static boolean wanted;
    /** The terrain in the cache is out of date and is drawn again this frame. */
    private static boolean refresh;
    private static boolean cached;
    private static int cachedResolution;
    private static long cachedMeshVersion = -1;
    private static volatile long meshVersion;
    private static int refreshes;
    private static int reuses;

    private static final SubmitNodeStorage PLAYER_SUBMITS = new SubmitNodeStorage();
    private static @Nullable RenderBuffers playerBuffers;
    private static @Nullable FeatureRenderDispatcher playerFeatures;

    private ShadowPass() {
    }

    /** Something outside this class changed what the cached terrain would look like; draw it again. */
    public static void invalidate() {
        cached = false;
    }

    /** Called when a section of terrain gets a new mesh: the cached shadow of it is out of date. */
    public static void meshChanged() {
        meshVersion++;
    }

    /** How often the terrain was drawn and how often the cache stood in for it, for the debug script. */
    public static String summary() {
        return "shadow terrain drawn " + refreshes + ", reused " + reuses;
    }

    /**
     * The matrix a standard pack's programs read as {@code shadowModelView}: camera-relative world space (the camera of this frame, or with
     * {@code atRefresh} the camera the cached terrain is drawn from, which is where the terrain's positions are relative to) to the light's view.
     */
    static Matrix4f shadowView(final boolean atRefresh) {
        return atRefresh ? new Matrix4f(LIGHT_VIEW) : new Matrix4f(SHADOW_VIEW);
    }

    static Matrix4f shadowProjection() {
        return new Matrix4f(SHADOW_PROJECTION);
    }

    /** Works out this frame's shadow matrix. Sets the shadow strength to zero when no map will be drawn. */
    static void prepare(final CameraRenderState cameraState, final ShaderGlobals globals) {
        IrisPlan plan = IrisPipeline.currentPlan();
        wanted = plan != null && plan.hasShadow() && Shaders.celestialLight() && globals.lightDir.w > 0.0F;
        if (!wanted) {
            globals.lightDir.w = 0.0F;
            SHADOW_VIEW.identity();
            SHADOW_PROJECTION.identity();
            cached = false;
            return;
        }

        Vec3 now = cameraState.pos;
        // The map reaches the shadow distance from the player wherever the player is within the margin of its center.
        float wantedRange = plan.shadowDistance() + MARGIN;
        NOW_TO_LIGHT.set(globals.lightDir.x, globals.lightDir.y, globals.lightDir.z).normalize();
        int resolution = plan.shadowResolution();

        refresh = NO_CACHE || !cached
                || resolution != cachedResolution
                || wantedRange != range
                || meshVersion != cachedMeshVersion
                || NOW_TO_LIGHT.dot(TO_LIGHT) < LIGHT_TOLERANCE_DOT
                || now.distanceToSqr(camera) > MARGIN * MARGIN;
        if (refresh) {
            camera = now;
            range = wantedRange;
            // Deep enough for anything that can cast a shadow into the map from above at the angles the sun is strong at.
            depth = range * 1.5F + 64.0F;
            TO_LIGHT.set(NOW_TO_LIGHT);
            cachedResolution = resolution;
            cachedMeshVersion = meshVersion;

            // Light space: looking along the light, so z grows towards the light.
            LIGHT_VIEW.setLookAlong(-TO_LIGHT.x, -TO_LIGHT.y, -TO_LIGHT.z, NORTH.x, NORTH.y, NORTH.z);

        }

        // Positions arrive relative to the player's camera now; the map is centered on where the camera was.
        SHADOW_VIEW.set(LIGHT_VIEW).translate((float) (now.x - camera.x), (float) (now.y - camera.y), (float) (now.z - camera.z));
        SHADOW_PROJECTION.identity().ortho(-range, range, -range, range, -depth, depth);
    }

    /** @param features everything the game is about to draw this frame besides terrain: mobs, block entities, items */
    static void render(final FeatureRenderDispatcher.PreparedFrame features) {
        if (!wanted) return;
        RenderTarget target = IrisPipeline.targets().shadow();
        RenderTarget cache = IrisPipeline.targets().shadowCache();
        int size = IrisPipeline.targets().shadowResolution();
        if (target == null || cache == null) return;
        Shaders.setPhase(Shaders.PHASE_SHADOW);
        if (refresh) {
            clear(target, 1.0);
            // The terrain's positions are relative to the camera it is drawn from, so its programs see the light's view as it is there.
            IrisPipeline.writeShadowUniforms(true);
            ShadowTerrain.render(target, camera, LIGHT_VIEW, TO_LIGHT, range, depth);
            copy(target, cache, size);
            cached = true;
            refresh = false;
            refreshes++;
        } else {
            copy(cache, target, size);
            reuses++;
        }
        IrisPipeline.writeShadowUniforms(false);

        // The game's own draw code for models, run once more with its output pointed at the shadow map. The
        // replaced model shaders place their vertices where the light sees them in this phase, and
        // ShaderBindings.skipsDraws drops whatever is drawn with any other shader (text, lines, outlines).
        // The translucent phase matters: a player's skin is drawn as a translucent model, for its outer layer.
        RenderSystem.outputColorTextureOverride = target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        try {
            features.executeSolid();
            features.executeTranslucent();
            drawPlayer();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }
        Shaders.setPhase(Shaders.PHASE_WORLD);
    }

    private static void copy(final RenderTarget from, final RenderTarget to, final int size) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(from.getColorTexture(), to.getColorTexture(), 0, 0, 0, 0, 0, size, size);
        encoder.copyTextureToTexture(from.getDepthTexture(), to.getDepthTexture(), 0, 0, 0, 0, 0, size, size);
    }

    /**
     * In first person the game never draws the player, so it is not among the models above and would cast no
     * shadow. Here the player is extracted and drawn the way any mob is, into the shadow map only, with a
     * feature dispatcher of this mod's own: the game's is busy with the frame being drawn.
     */
    private static void drawPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameRenderer.mainCamera().isDetached() || player.isSleeping()) return;
        if (playerFeatures == null) {
            playerBuffers = new RenderBuffers(0);
            playerFeatures = new FeatureRenderDispatcher(playerBuffers, minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font, minecraft.gameRenderer.gameRenderState());
        }
        EntityRenderDispatcher entities = minecraft.levelRenderer.entityRenderDispatcher();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        EntityRenderState state = entities.extractEntity(player, partialTick);
        CameraRenderState cameraState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Vec3 cameraPos = cameraState.pos;
        entities.submit(state, cameraState, state.x - cameraPos.x, state.y - cameraPos.y, state.z - cameraPos.z, new PoseStack(), PLAYER_SUBMITS);
        playerFeatures.renderAllFeatures(PLAYER_SUBMITS);
    }

    /** End of the frame: the player's vertex buffers are rotated the way the game rotates its own. */
    static void endFrame() {
        if (playerBuffers != null) playerBuffers.endFrame();
    }

    /**
     * Empties a shadow map: nothing in it casts a shadow. Done as a real pass and not through the game's clear
     * call, which Metallum only notes down and carries out when the texture is next used, possibly in the middle
     * of someone else's pass.
     */
    static void clear(final RenderTarget shadow) {
        clear(shadow, 0.0);
    }

    /** @param depth what the map's depth is set to: 0 for the built-in shaders' (the nearest to the light has the greatest), 1 for OpenGL's */
    static void clear(final RenderTarget shadow, final double depth) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Metallum Extra shadow clear", shadow.getColorTextureView(), Optional.of(CLEAR_COLOR),
                shadow.getDepthTextureView(), OptionalDouble.of(depth))) {
        }
    }

    static void close() {
        cached = false;
        ShadowTerrain.close();
        if (playerFeatures != null) {
            playerFeatures.close();
            playerFeatures = null;
        }
        if (playerBuffers != null) {
            playerBuffers.close();
            playerBuffers = null;
        }
    }
}
