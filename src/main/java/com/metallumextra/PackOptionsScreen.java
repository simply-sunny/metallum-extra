package com.metallumextra;

import com.metallumextra.shader.pack.PackOption;
import com.metallumextra.shader.pack.PackOptions;
import com.metallumextra.shader.pack.PackManager;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import com.metallumextra.shader.pack.ShaderPack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.metallumextra.shader.pack.PackProfiles;
import com.metallumextra.shader.pack.PackScreens;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.layouts.SpacerElement;
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
    /** The page of the pack's settings shown ({@code ""} is the first) and where Escape or a right click goes back to. */
    private final String screenName;
    private final Screen back;
    private final PackScreens screens;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private int page;
    private final PackOptions.Draft draft;
    private Button done;
    /** What is typed in the search box: every option of the pack at any depth that fuzzily matches it is listed in place of the page. */
    private String query = "";
    private net.minecraft.client.gui.components.EditBox search;
    private boolean searchFocused;

    public PackOptionsScreen(final ShaderPackScreen parent, final @org.jspecify.annotations.Nullable ShaderPack pack) {
        this(parent, pack, "", parent);
    }

    /** A page of the pack's settings (for the debug script); going back from it leaves the screen. */
    public static PackOptionsScreen page(final ShaderPackScreen parent, final String screenName) {
        return new PackOptionsScreen(parent, null, screenName, parent);
    }

    private PackOptionsScreen(final ShaderPackScreen parent, final @org.jspecify.annotations.Nullable ShaderPack pack, final String screenName, final Screen back) {
        super(Component.literal((pack != null ? pack : PackManager.active()).name() + " Options"));
        this.parent = parent;
        this.pack = pack != null ? pack : PackManager.active();
        this.screenName = screenName;
        this.back = back;
        this.draft = parent.optionsFor(this.pack);
        this.screens = PackScreens.of(this.pack, this.pack.options());
    }

    @Override
    protected void init() {
        layout.removeChildren();
        layout.addTitleHeader(screenName.isEmpty() ? title : Component.literal(title.getString() + ": " + screens.screenLabel(screenName)), font);

        List<PackOption> options = pack.options();
        searchFocused = searchFocused && search != null && search.isFocused();
        // The pack's profiles (low, medium ...) are one button: choosing one sets the options it lists, and the options below show it.
        Map<String, Map<String, String>> profiles = PackProfiles.of(ProgramSet.discover(pack, null));
        List<PackScreens.Item> items = new ArrayList<>(screens.items(screenName));
        if (screenName.isEmpty() && query.isBlank() && !profiles.isEmpty() && items.stream().noneMatch(i -> i instanceof PackScreens.Profile)) items.add(0, new PackScreens.Profile());
        boolean searching = !query.isBlank();
        if (searching) {
            List<PackScreens.Item> found = new ArrayList<>();
            List<int[]> scored = new ArrayList<>();
            for (int i = 0; i < options.size(); i++) {
                PackOption option = options.get(i);
                int score = fuzzy(query, screens.optionLabel(option) + " " + option.id() + " " + java.util.Objects.requireNonNullElse(screens.optionComment(option), ""), screens.optionLabel(option));
                if (score > 0) scored.add(new int[] {score, i});
            }
            scored.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[1], b[1]));
            for (int[] hit : scored) found.add(new PackScreens.OptionItem(options.get(hit[1])));
            items = found;
        }
        int perPage = PER_PAGE;
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(6));
        search = rows.addChild(new net.minecraft.client.gui.components.EditBox(font, BUTTON_WIDTH * 2, 20, Component.literal("Search")));
        search.setHint(Component.literal("Search all settings..."));
        search.setMaxLength(60);
        search.setValue(query);
        search.setResponder(text -> {
            if (text.equals(query)) return;
            query = text;
            page = 0;
            searchFocused = true;
            rebuildWidgets();
        });
        GridLayout grid = rows.addChild(new GridLayout());
        grid.defaultCellSetting().padding(3);
        int columns = screens.columns(screenName);
        GridLayout.RowHelper cells = grid.createRowHelper(columns);
        int width = columns == 1 ? BUTTON_WIDTH * 2 : columns == 2 ? BUTTON_WIDTH : 150;
        for (PackScreens.Item item : items.subList(page * perPage, Math.min(items.size(), (page + 1) * perPage))) {
            if (item instanceof PackScreens.OptionItem entry) {
                cells.addChild(cycle(entry.option(), width));
            } else if (item instanceof PackScreens.Link link) {
                Button button = Button.builder(Component.literal(screens.screenLabel(link.screen()) + "..."),
                        b -> minecraft.gui.setScreen(new PackOptionsScreen(parent, pack, link.screen(), this))).width(width).build();
                String comment = screens.screenComment(link.screen());
                if (comment != null) button.setTooltip(Tooltip.create(Component.literal(comment)));
                cells.addChild(button);
            } else if (item instanceof PackScreens.Profile && !profiles.isEmpty()) {
                List<String> names = new ArrayList<>(profiles.keySet());
                names.add("Custom");
                String current = PackProfiles.current(options, draft::get, profiles);
                cells.addChild(CycleButton.<String>builder(Component::literal, current == null ? "Custom" : current)
                        .withValues(names)
                        .create(0, 0, width, 20, Component.literal("Profile"), (widget, value) -> {
                            if (!value.equals("Custom")) {
                                for (String problem : PackProfiles.apply(options, profiles, value, draft::set)) MetallumExtra.LOGGER.warn("[Metallum Extra] {}: {}", pack.name(), problem);
                                done.setMessage(parent.doneLabel());
                                rebuildWidgets();
                            }
                        }));
            } else {
                cells.addChild(SpacerElement.width(width));
            }
        }

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        if (pages > 1) {
            footer.addChild(Button.builder(Component.literal("<"), b -> turn(-1)).width(20).build()).active = page > 0;
            footer.addChild(Button.builder(Component.literal("Page " + (page + 1) + "/" + pages), b -> { }).width(60).build()).active = false;
            footer.addChild(Button.builder(Component.literal(">"), b -> turn(1)).width(20).build()).active = page < pages - 1;
        }
        footer.addChild(Button.builder(Component.literal("Reset"), b -> reset()).width(60).build());
        if (back instanceof PackOptionsScreen) {
            footer.addChild(Button.builder(Component.literal("Back"), b -> onClose()).width(60).build());
        }
        done = footer.addChild(Button.builder(parent.doneLabel(), b -> {
            if (parent.changed()) parent.applyAndClose();
            else minecraft.gui.setScreen(parent);
        }).width(100).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        if (searchFocused) setFocused(search);
    }

    /**
     * How well {@code query} matches {@code text}: zero for no match. Each word of the query must be in the text, either as it is (more points when it starts
     * the label) or as scattered letters in order ("shdwq" finds "Shadow Quality").
     */
    static int fuzzy(final String query, final String text, final String label) {
        String haystack = text.toLowerCase(java.util.Locale.ROOT), head = label.toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String word : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (head.startsWith(word)) score += 30;
            else if (head.contains(word)) score += 20;
            else if (haystack.contains(word)) score += 10;
            else {
                int at = 0, matched = 0;
                for (int i = 0; i < word.length(); i++) {
                    int found = haystack.indexOf(word.charAt(i), at);
                    if (found < 0) return 0;
                    matched++;
                    at = found + 1;
                }
                // Letters far apart are a weaker match than letters together.
                score += Math.max(1, 6 - (at - matched) / 8);
            }
        }
        return score;
    }

    private CycleButton<String> cycle(final PackOption option, final int width) {
        String current = option.values().get(draft.get(option));
        String comment = screens.optionComment(option);
        CycleButton.Builder<String> builder = CycleButton.<String>builder(value -> Component.literal(screens.valueLabel(option, value)), current)
                .withValues(option.values());
        if (comment != null) builder.withTooltip(value -> Tooltip.create(Component.literal(comment)));
        return builder.create(0, 0, width, 20, Component.literal(screens.optionLabel(option)), (widget, value) -> {
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
        return event.key() == InputConstants.KEY_ESCAPE || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(final KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyReleased(event);
    }

    /** A right click on a setting moves it back (Escape goes back a page). */
    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (CycleRightClick.handle(this, event)) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(back);
    }
}
