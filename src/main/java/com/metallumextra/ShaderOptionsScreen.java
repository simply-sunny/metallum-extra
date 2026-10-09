package com.metallumextra;

import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ShaderPack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** How the shaders look and what they cost: the quality preset and each effect. Every change applies right away. */
public final class ShaderOptionsScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;

    private final Screen parent;
    private final ShaderPack pack;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    /** @param pack the pack whose own options are offered below the mod's settings; null for the one in use */
    public ShaderOptionsScreen(final Screen parent, final @org.jspecify.annotations.Nullable ShaderPack pack) {
        super(Component.literal("Shader Options"));
        this.parent = parent;
        this.pack = pack != null ? pack : PackManager.active();
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);

        LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(6));
        GridLayout grid = rows.addChild(new GridLayout());
        grid.defaultCellSetting().padding(3);
        GridLayout.RowHelper cells = grid.createRowHelper(2);
        cells.addChild(ExtraConfigScreen.choice(Settings.shaderQuality()));
        cells.addChild(ExtraConfigScreen.choice(Settings.shadowPixelSize()));
        for (Settings.Toggle toggle : Settings.shaders()) {
            cells.addChild(ExtraConfigScreen.button(toggle));
        }

        if (!pack.options().isEmpty()) {
            Button own = rows.addChild(Button.builder(Component.literal(pack.name() + " Options..."),
                    button -> minecraft.gui.setScreen(new PackOptionsScreen(this, pack))).width(BUTTON_WIDTH * 2 + 6).build());
            own.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    "Settings that this shader pack offers itself.")));
        }

        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(BUTTON_WIDTH).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
