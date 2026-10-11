package com.metallumextra.mixin.shader;

import com.metallumextra.shader.Shaders;
import com.metallumextra.shader.IrisWorld;
import com.mojang.renderpearl.api.commands.RenderPass;
import org.joml.Vector3fc;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shaders: under an open sky the shader pipeline draws the sky itself, in place of the game's sky disc, its
 * sunrise fan and the dark disc it puts below the horizon. The sun, moon and stars stay the game's, drawn on
 * the tilted path the lighting uses.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererShaderMixin {
    @Unique
    private boolean metallumExtra$tilted;

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$ownSky(final RenderPass renderPass, final Vector3fc skyColor, final CallbackInfo ci) {
        if (Shaders.drawsSky()) {
            IrisWorld.drawSky(renderPass);
            ci.cancel();
        }
    }

    /** The drawn sun, moon and stars follow the tilted path the lighting uses. */
    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void metallumExtra$tiltSunPath(final RenderPass renderPass, final PoseStack poseStack, final float sunAngle, final float moonAngle, final float starAngle,
                                           final MoonPhase moonPhase, final float rainBrightness, final float starBrightness, final CallbackInfo ci) {
        this.metallumExtra$tilted = Shaders.active() && Shaders.phase() == Shaders.PHASE_WORLD;
        if (this.metallumExtra$tilted) {
            poseStack.pushPose();
            poseStack.rotate(Axis.XP, Shaders.sunPathTilt());
        }
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("RETURN"))
    private void metallumExtra$untiltSunPath(final RenderPass renderPass, final PoseStack poseStack, final float sunAngle, final float moonAngle, final float starAngle,
                                             final MoonPhase moonPhase, final float rainBrightness, final float starBrightness, final CallbackInfo ci) {
        if (this.metallumExtra$tilted) {
            poseStack.popPose();
            this.metallumExtra$tilted = false;
        }
    }

    /** Which of the sun and the moon the game is drawing, for the pack's {@code renderStage}. */
    @Inject(method = "renderSun", at = @At("HEAD"))
    private void metallumExtra$sunBegins(final CallbackInfo ci) {
        IrisWorld.skyStage = IrisWorld.STAGE_SUN;
    }

    @Inject(method = "renderSun", at = @At("RETURN"))
    private void metallumExtra$sunEnds(final CallbackInfo ci) {
        IrisWorld.skyStage = 0;
    }

    @Inject(method = "renderMoon", at = @At("HEAD"))
    private void metallumExtra$moonBegins(final CallbackInfo ci) {
        IrisWorld.skyStage = IrisWorld.STAGE_MOON;
    }

    @Inject(method = "renderMoon", at = @At("RETURN"))
    private void metallumExtra$moonEnds(final CallbackInfo ci) {
        IrisWorld.skyStage = 0;
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$noSunriseFan(final CallbackInfo ci) {
        if (Shaders.drawsSky()) ci.cancel();
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void metallumExtra$noDarkDisc(final CallbackInfo ci) {
        if (Shaders.drawsSky()) ci.cancel();
    }
}
