package com.metallumextra.mixin.shader;

import com.llamalad7.mixinextras.sugar.Local;
import com.metallumextra.shader.Shaders;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shaders: tells the shader pipeline where the frame is, since its replaced shaders draw menus and the world alike. */
@Mixin(GameRenderer.class)
public abstract class GameRendererShaderMixin implements com.metallumextra.shader.HandSource {
    @Unique
    private static final String DRAW_LEVEL = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V";

    /** A pack that draws its own clouds turns the game's off for as long as it is in use, without touching the player's option. */
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCloudStatus()Lnet/minecraft/client/CloudStatus;"))
    private net.minecraft.client.CloudStatus metallumExtra$packClouds(final net.minecraft.client.CloudStatus chosen) {
        return com.metallumextra.shader.IrisPipeline.hidesClouds() ? net.minecraft.client.CloudStatus.OFF : chosen;
    }

    @Inject(method = "render()V", at = @At("HEAD"))
    private void metallumExtra$beginFrame(final CallbackInfo ci) {
        Shaders.beginFrame();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = DRAW_LEVEL))
    private void metallumExtra$beginWorld(final CallbackInfo ci, @Local final Matrix4f projectionMatrix) {
        Shaders.beginWorld(projectionMatrix);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = DRAW_LEVEL, shift = At.Shift.AFTER))
    private void metallumExtra$afterWorld(final CallbackInfo ci) {
        Shaders.afterWorld();
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void metallumExtra$afterHand(final CallbackInfo ci) {
        // The item drawn ahead of the rest goes through here too, before the deferred programs: the picture is finished only after the game's own turn.
        if (this.metallumExtra$drawingEarly) return;
        Shaders.setHandPart(Shaders.HAND_ALL);
        Shaders.afterHand();
    }

    @Inject(method = "renderLevel()V", at = @At("RETURN"))
    private void metallumExtra$endWorld(final CallbackInfo ci) {
        if (metallumExtra$handBuffers != null) metallumExtra$handBuffers.endFrame();
        // The game's turn for the item may not have come (another view, or the hud hidden): the next frame starts afresh.
        this.metallumExtra$handDrawn = false;
        Shaders.setHandPart(Shaders.HAND_ALL);
        Shaders.endWorld();
        com.metallumextra.fx.Upscale.end(this.mainRenderTarget);
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private com.mojang.blaze3d.pipeline.RenderTarget hud3DTarget;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.Projection hudProjection;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.ProjectionMatrixBuffer hud3dProjectionMatrixBuffer;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.state.GameRenderState gameRenderState;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    @org.spongepowered.asm.mixin.Mutable
    private net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureRenderDispatcher;

    /**
     * The item has its own feature dispatcher while it is drawn ahead of the rest: the game's has one prepared frame at a time, and the level's is still in use
     * (its translucent things come after this).
     */
    @Unique
    private static net.minecraft.client.renderer.feature.FeatureRenderDispatcher metallumExtra$handDispatcher;
    @Unique
    private static net.minecraft.client.renderer.RenderBuffers metallumExtra$handBuffers;

    @org.spongepowered.asm.mixin.Shadow
    private void renderItemInHand(final net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
                                  final net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
                                  final com.mojang.renderpearl.api.textures.GpuTextureView depthTextureView) {
        throw new AssertionError();
    }

    @Unique
    private boolean metallumExtra$handDrawn;
    @Unique
    private boolean metallumExtra$drawingEarly;

    @Override
    public void metallumExtra$drawHandNow() {
        net.minecraft.client.renderer.state.level.CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
        net.minecraft.client.renderer.state.level.PlayerRenderState playerState = this.gameRenderState.levelRenderState.playerRenderState;
        // The same set-up the game makes for the item (see GameRenderer#render3dHud), with the world's projection put back afterwards.
        com.mojang.renderpearl.api.buffers.GpuBufferSlice worldProjection = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrixBuffer();
        com.mojang.blaze3d.ProjectionType worldType = com.mojang.blaze3d.systems.RenderSystem.getProjectionType();
        this.hudProjection.setupPerspective(0.05F, cameraState.depthFar, cameraState.hudFov, this.gameRenderState.windowRenderState.width, this.gameRenderState.windowRenderState.height);
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(this.hud3dProjectionMatrixBuffer.getBuffer(this.hudProjection), com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(this.hud3DTarget.getDepthTexture(), 0.0);
        net.minecraft.client.renderer.feature.FeatureRenderDispatcher own = this.featureRenderDispatcher;
        if (metallumExtra$handDispatcher == null) {
            FeatureRenderDispatcherAccessor parts = (FeatureRenderDispatcherAccessor) own;
            metallumExtra$handBuffers = new net.minecraft.client.renderer.RenderBuffers(1);
            metallumExtra$handDispatcher = new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(metallumExtra$handBuffers, parts.metallumExtra$modelManager(),
                    parts.metallumExtra$atlasManager(), parts.metallumExtra$font(), parts.metallumExtra$gameRenderState());
        }
        this.featureRenderDispatcher = metallumExtra$handDispatcher;
        // The level has the camera's rotation on the model-view stack while it is drawn; the item brings its own, as it does after the level.
        org.joml.Matrix4fStack modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        this.metallumExtra$drawingEarly = true;
        try {
            this.renderItemInHand(cameraState, playerState, this.hud3DTarget.getDepthTextureView());
        } finally {
            this.metallumExtra$drawingEarly = false;
            modelView.popMatrix();
            this.featureRenderDispatcher = own;
        }
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(worldProjection, worldType);
        this.metallumExtra$handDrawn = true;
    }

    @Override
    public com.mojang.renderpearl.api.textures.GpuTextureView metallumExtra$handDepth() {
        return this.hud3DTarget.getDepthTextureView();
    }

    /** The solid part of the item was drawn ahead of the rest of the picture: the game's own turn for it draws what is left (translucent items, the glint). */
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void metallumExtra$handAlreadyDrawn(final CallbackInfo ci) {
        if (this.metallumExtra$drawingEarly || !this.metallumExtra$handDrawn) return;
        this.metallumExtra$handDrawn = false;
        Shaders.setHandPart(Shaders.HAND_REST);
    }

    /** MetalFX: while the world is drawn, the game's image is the smaller one. */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void metallumExtra$beginUpscale(final CallbackInfo ci) {
        com.metallumextra.fx.Upscale.begin(this.mainRenderTarget);
    }
}
