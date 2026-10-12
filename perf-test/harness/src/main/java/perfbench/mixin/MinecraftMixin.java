package perfbench.mixin;

import perfbench.Bench;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Calls the bench at the start and end of every frame. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void perfbench$begin(final boolean advance, final CallbackInfo ci) {
        Bench.frameBegin((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void perfbench$end(final boolean advance, final CallbackInfo ci) {
        Bench.frameEnd();
    }
}
