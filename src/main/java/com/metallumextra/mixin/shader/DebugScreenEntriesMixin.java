package com.metallumextra.mixin.shader;

import com.metallumextra.shader.DebugInfo;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the F3 screen look up Metallum Extra's entry by its id. */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
    @Inject(method = "getEntry", at = @At("HEAD"), cancellable = true)
    private static void metallumExtra$entry(final Identifier id, final CallbackInfoReturnable<DebugScreenEntry> cir) {
        if (DebugInfo.ID.equals(id)) cir.setReturnValue(DebugInfo.ENTRY);
    }
}
