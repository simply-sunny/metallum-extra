package com.metallumextra.shader;

import com.metallumextra.MetallumExtra;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;

/** Metallum Extra's entry of the F3 screen (see the mixins that add it to the game's list). */
public final class DebugInfo {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(MetallumExtra.MOD_ID, "info");
    public static final DebugScreenEntry ENTRY = (displayer, level, clientChunk, serverChunk) -> {
        java.util.List<String> lines = IrisPipeline.debugLines();
        // The game's priority lines alternate between the two columns; Iris's own sit together on the left, and so do these.
        try {
            var field = displayer.getClass().getDeclaredField("val$leftLines");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<String> left = (java.util.List<String>) field.get(displayer);
            left.addAll(lines);
        } catch (ReflectiveOperationException | RuntimeException e) {
            for (String line : lines) displayer.addPriorityLine(line);
        }
    };

    private DebugInfo() {
    }
}
