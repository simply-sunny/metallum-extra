package com.metallumextra.mixin.shader;

import com.metallumextra.shader.DebugInfo;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

/** Adds Metallum Extra's entry to the ones the F3 screen shows while the overlay is visible. */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {
    @Shadow
    public abstract boolean isOverlayVisible();

    @Inject(method = "getCurrentlyEnabled", at = @At("RETURN"), cancellable = true)
    private void metallumExtra$enabled(final CallbackInfoReturnable<Collection<Identifier>> cir) {
        if (!this.isOverlayVisible()) return;
        Collection<Identifier> all = new ArrayList<>(cir.getReturnValue());
        if (!all.contains(DebugInfo.ID)) all.add(DebugInfo.ID);
        cir.setReturnValue(all);
    }
}
