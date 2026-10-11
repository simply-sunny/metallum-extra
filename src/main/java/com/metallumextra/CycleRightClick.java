package com.metallumextra;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/** A right click on a setting moves it the other way: a value back, or an on to an off. (The left click, and the scroll wheel the other way, already go forward.) */
final class CycleRightClick {
    private CycleRightClick() {
    }

    /** @return whether a setting under the pointer was moved back */
    static boolean handle(final Screen screen, final MouseButtonEvent event) {
        if (event.button() != 1) return false;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof CycleButton<?> button && button.active && button.visible && button.isMouseOver(event.x(), event.y())) {
                button.mouseScrolled(event.x(), event.y(), 0.0, 1.0);
                button.playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
        }
        return false;
    }
}
