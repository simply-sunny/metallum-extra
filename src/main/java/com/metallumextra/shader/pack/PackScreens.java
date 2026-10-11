package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How a pack lays out its settings, as Iris reads it: {@code screen=} in {@code shaders.properties} lists what the first page holds, and
 * {@code screen.<name>=} what each page it links to ({@code [name]}) holds. A line is option names, {@code [page]} links, {@code <profile>}
 * (the profile button), {@code <empty>} (a gap) and {@code *} (every option no page lists). {@code screen.columns} / {@code screen.<name>.columns}
 * set the width. The words the player reads come from {@code lang/en_us.lang}: {@code option.X}, {@code option.X.comment}, {@code value.X.<value>},
 * {@code screen.<name>} and {@code screen.<name>.comment}. A pack with no {@code screen=} gets one flat list.
 */
public final class PackScreens {
    /** One place on a page. */
    public sealed interface Item permits OptionItem, Link, Gap, Profile {
    }

    public record OptionItem(PackOption option) implements Item {
    }

    public record Link(String screen) implements Item {
    }

    public record Gap() implements Item {
    }

    public record Profile() implements Item {
    }

    private final Map<String, String> lang = new LinkedHashMap<>();
    private final Map<String, PackOption> byId = new LinkedHashMap<>();
    private final ProgramSet set;
    private final List<PackOption> options;

    private PackScreens(final ShaderPack pack, final List<PackOption> options) {
        this.options = options;
        this.set = ProgramSet.discover(pack, null);
        for (PackOption option : options) byId.put(option.id(), option);
        for (String path : List.of("lang/en_us.lang", "lang/en_US.lang")) {
            String text = pack.read(path);
            if (text == null) continue;
            for (String line : text.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                int equals = trimmed.indexOf('=');
                if (equals > 0) lang.putIfAbsent(trimmed.substring(0, equals).strip(), trimmed.substring(equals + 1).strip());
            }
        }
    }

    public static PackScreens of(final ShaderPack pack, final List<PackOption> options) {
        return new PackScreens(pack, options);
    }

    /** Whether the pack describes its pages (otherwise {@link #items} is one flat list). */
    public boolean hasLayout() {
        return set.property("screen") != null;
    }

    /** What a page holds; {@code ""} is the first page. */
    public List<Item> items(final String screen) {
        String key = screen.isEmpty() ? "screen" : "screen." + screen;
        String line = set.property(key);
        List<Item> items = new ArrayList<>();
        if (line == null) {
            if (screen.isEmpty()) for (PackOption option : options) items.add(new OptionItem(option));
            return items;
        }
        Set<String> listed = null;
        for (String token : line.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.equals("<empty>")) items.add(new Gap());
            else if (token.equals("<profile>")) items.add(new Profile());
            else if (token.startsWith("[") && token.endsWith("]")) items.add(new Link(token.substring(1, token.length() - 1)));
            else if (token.equals("*")) {
                if (listed == null) listed = listedAnywhere();
                for (PackOption option : options) if (!listed.contains(option.id())) items.add(new OptionItem(option));
            } else if (byId.containsKey(token)) items.add(new OptionItem(byId.get(token)));
        }
        return items;
    }

    private Set<String> listedAnywhere() {
        Set<String> listed = new HashSet<>();
        for (String key : set.orderedProperties().keySet()) {
            if (!key.equals("screen") && !(key.startsWith("screen.") && !key.endsWith(".columns"))) continue;
            String line = set.property(key);
            if (line == null) continue;
            for (String token : line.trim().split("\\s+")) listed.add(token);
        }
        return listed;
    }

    public int columns(final String screen) {
        String value = set.property(screen.isEmpty() ? "screen.columns" : "screen." + screen + ".columns");
        if (value == null) return 2;
        try {
            return Math.max(1, Math.min(3, Integer.parseInt(value.strip())));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** What a page is called; the first page has no name of its own. */
    public String screenLabel(final String screen) {
        String text = lang.get("screen." + screen);
        return text != null ? clean(text) : pretty(screen);
    }

    public @Nullable String screenComment(final String screen) {
        String text = lang.get("screen." + screen + ".comment");
        return text == null ? null : clean(text);
    }

    public String optionLabel(final PackOption option) {
        String text = lang.get("option." + option.id());
        if (text != null) return clean(text);
        return option.label().equals(option.id()) ? pretty(option.id()) : option.label();
    }

    public @Nullable String optionComment(final PackOption option) {
        String text = lang.get("option." + option.id() + ".comment");
        return text == null ? null : clean(text);
    }

    public String valueLabel(final PackOption option, final String value) {
        String text = lang.get("value." + option.id() + "." + value);
        return text != null ? clean(text) : value;
    }

    /** A name made from an identifier: {@code SHADOW_QUALITY} and {@code shadowMapResolution} are "Shadow Quality" and "Shadow Map Resolution". */
    public static String prettyForTest(final String id) {
        return pretty(id);
    }

    static String pretty(final String id) {
        String spaced = id.replace('_', ' ').replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").strip();
        StringBuilder out = new StringBuilder();
        for (String word : spaced.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** Colour codes ({@code §a}) and line breaks ({@code \n}) of a lang line. */
    private static String clean(final String text) {
        return text.replaceAll("§.", "").replace("\\n", "\n");
    }
}
