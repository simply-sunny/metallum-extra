package com.metallumextra;

import com.metallumextra.shader.Shaders;
import com.metallumextra.shader.pack.PackOption;
import com.metallumextra.shader.pack.PackOptions;
import com.metallumextra.shader.pack.PackManager;
import com.metallumextra.shader.pack.ShaderPack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The settings a shader pack offers itself (its {@code options} in {@code pack.json}). Each is saved as it is changed;
 * the shaders are compiled again with the new values when the screen is left, so a run of changes costs one reload.
 */
public final class PackOptionsScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int PER_PAGE = 12;

    private final Screen parent;
    private final ShaderPack pack;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private int page;
    private boolean changed;

    public PackOptionsScreen(final Screen parent, final ShaderPack pack) {
        super(Component.literal(pack.name() + " Options"));
        this.parent = parent;
        this.pack = pack;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);

        List<PackOption> options = pack.options();
        int pages = Math.max(1, (options.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);

        LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(6));
        GridLayout grid = rows.addChild(new GridLayout());
        grid.defaultCellSetting().padding(3);
        GridLayout.RowHelper cells = grid.createRowHelper(2);
        for (PackOption option : options.subList(page * PER_PAGE, Math.min(options.size(), (page + 1) * PER_PAGE))) {
            cells.addChild(cycle(option));
        }

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        if (pages > 1) {
            footer.addChild(Button.builder(Component.literal("<"), b -> turn(-1)).width(20).build()).active = page > 0;
            footer.addChild(Button.builder(Component.literal("Page " + (page + 1) + "/" + pages), b -> { }).width(60).build()).active = false;
            footer.addChild(Button.builder(Component.literal(">"), b -> turn(1)).width(20).build()).active = page < pages - 1;
        }
        footer.addChild(Button.builder(Component.literal("Reset"), b -> reset()).width(60).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(100).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private CycleButton<String> cycle(final PackOption option) {
        String current = option.values().get(PackOptions.get(pack.name(), option));
        return CycleButton.<String>builder(Component::literal, current)
                .withValues(option.values())
                .create(0, 0, BUTTON_WIDTH, 20, Component.literal(option.label()), (widget, value) -> {
                    PackOptions.set(pack.name(), option, option.values().indexOf(value));
                    changed = true;
                });
    }

    private void turn(final int by) {
        page += by;
        rebuildWidgets();
    }

    private void reset() {
        PackOptions.reset(pack.name(), pack.options());
        changed = true;
        rebuildWidgets();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        // Only the pack in use is compiled; any other reads its options when it is chosen.
        if (changed && pack.name().equals(PackManager.active().name())) Shaders.packOptionsChanged();
        minecraft.gui.setScreen(parent);
    }
}
