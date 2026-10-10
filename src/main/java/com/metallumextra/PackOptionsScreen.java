package com.metallumextra;

import com.metallumextra.shader.pack.PackOption;
import com.metallumextra.shader.pack.PackOptions;
import com.metallumextra.shader.pack.PackManager;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import com.metallumextra.shader.pack.ShaderPack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.metallumextra.shader.pack.PackProfiles;
import com.metallumextra.shader.pack.ProgramSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pack's paginated options. Edits stay in the parent menu's draft until Apply; Escape only goes back.
 */
public final class PackOptionsScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int PER_PAGE = 12;

    private final ShaderPackScreen parent;
    private final ShaderPack pack;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private int page;
    private final PackOptions.Draft draft;
    private Button done;

    public PackOptionsScreen(final ShaderPackScreen parent, final @org.jspecify.annotations.Nullable ShaderPack pack) {
        super(Component.literal((pack != null ? pack : PackManager.active()).name() + " Options"));
        this.parent = parent;
        this.pack = pack != null ? pack : PackManager.active();
        this.draft = parent.optionsFor(this.pack);
    }

    @Override
    protected void init() {
        layout.removeChildren();
        layout.addTitleHeader(title, font);

        List<PackOption> options = pack.options();
        int pages = Math.max(1, (options.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);

        LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(6));
        GridLayout grid = rows.addChild(new GridLayout());
        grid.defaultCellSetting().padding(3);
        GridLayout.RowHelper cells = grid.createRowHelper(2);
        // The pack's profiles (low, medium ...) are one button: choosing one sets the options it lists, and the options below show it.
        Map<String, Map<String, String>> profiles = PackProfiles.of(ProgramSet.discover(pack, null));
        if (!profiles.isEmpty() && page == 0) {
            List<String> names = new ArrayList<>(profiles.keySet());
            names.add("Custom");
            String current = PackProfiles.current(options, draft::get, profiles);
            cells.addChild(CycleButton.<String>builder(Component::literal, current == null ? "Custom" : current)
                    .withValues(names)
                    .create(0, 0, BUTTON_WIDTH * 2 + 6, 20, Component.literal("Profile"), (widget, value) -> {
                        if (!value.equals("Custom")) {
                            for (String problem : PackProfiles.apply(options, profiles, value, draft::set)) MetallumExtra.LOGGER.warn("[Metallum Extra] {}: {}", pack.name(), problem);
                            done.setMessage(parent.doneLabel());
                            rebuildWidgets();
                        }
                    }), 2);
        }
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
        done = footer.addChild(Button.builder(parent.doneLabel(), b -> {
            if (parent.changed()) parent.applyAndClose();
            else onClose();
        }).width(100).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private CycleButton<String> cycle(final PackOption option) {
        String current = option.values().get(draft.get(option));
        return CycleButton.<String>builder(Component::literal, current)
                .withValues(option.values())
                .create(0, 0, BUTTON_WIDTH, 20, Component.literal(option.label()), (widget, value) -> {
                    draft.set(option, option.values().indexOf(value));
                    done.setMessage(parent.doneLabel());
                });
    }

    private void turn(final int by) {
        page += by;
        rebuildWidgets();
    }

    private void reset() {
        draft.reset();
        rebuildWidgets();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    // Match the parent: consuming Escape on release prevents one key tap from closing both screens.
    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        return event.key() == GLFW.GLFW_KEY_ESCAPE || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(final KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
