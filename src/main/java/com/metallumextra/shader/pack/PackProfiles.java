package com.metallumextra.shader.pack;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The profiles of a pack: {@code profile.LOW = SHADOW_QUALITY=0 shadowDistance=96.0 !BLOOM ...} in {@code shaders.properties}, each a set of values for the pack's
 * options. Choosing a profile writes those values; there is no separate state, so a profile is the current one exactly when every option it lists has its value
 * (and the pack is on "custom" when none matches). A profile may list another ({@code profile.OTHER}) to start from its values.
 * <p>
 * A value is matched to the option's list by number when it is one ({@code 64.0} is the {@code 64} of {@code [64 96 128]}); one the option does not offer is
 * reported by {@link #apply}, not guessed at.
 */
public final class PackProfiles {
    private PackProfiles() {
    }

    /** The profiles of the pack in the order the file lists them: name to option values ("true" or "false" for a switch). */
    public static Map<String, Map<String, String>> of(final ProgramSet set) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : set.orderedProperties().entrySet()) {
            if (entry.getKey().startsWith("profile.")) raw.put(entry.getKey().substring("profile.".length()), entry.getValue());
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String name : raw.keySet()) result.put(name, resolve(name, raw, new ArrayList<>()));
        return result;
    }

    private static Map<String, String> resolve(final String name, final Map<String, String> raw, final List<String> chain) {
        Map<String, String> values = new LinkedHashMap<>();
        if (chain.contains(name)) return values;
        chain.add(name);
        for (String token : raw.getOrDefault(name, "").trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.startsWith("profile.")) {
                values.putAll(resolve(token.substring("profile.".length()), raw, chain));
            } else if (token.contains("=")) {
                values.put(token.substring(0, token.indexOf('=')), token.substring(token.indexOf('=') + 1));
            } else if (token.startsWith("!")) {
                values.put(token.substring(1), "false");
            } else {
                values.put(token, "true");
            }
        }
        return values;
    }

    private static boolean same(final String a, final String b) {
        if (a.equals(b)) return true;
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static @Nullable PackOption option(final List<PackOption> options, final String id) {
        for (PackOption option : options) {
            if (option.id().equals(id)) return option;
        }
        return null;
    }

    /** The index in the option's list that stands for this profile value, or -1. */
    private static int index(final PackOption option, final String value) {
        if (option.toggle()) return value.equals("true") || value.equals("1") ? 1 : value.equals("false") || value.equals("0") ? 0 : -1;
        for (int i = 0; i < option.values().size(); i++) {
            if (same(option.values().get(i), value)) return i;
        }
        return -1;
    }

    /** The name of the profile whose values are all set, or null for custom settings. The first of those listed wins. */
    public static @Nullable String current(final List<PackOption> options, final java.util.function.ToIntFunction<PackOption> chosen, final Map<String, Map<String, String>> profiles) {
        for (Map.Entry<String, Map<String, String>> profile : profiles.entrySet()) {
            boolean matches = true;
            for (Map.Entry<String, String> value : profile.getValue().entrySet()) {
                PackOption option = option(options, value.getKey());
                if (option == null) continue;
                if (chosen.applyAsInt(option) != index(option, value.getValue())) {
                    matches = false;
                    break;
                }
            }
            if (matches) return profile.getKey();
        }
        return null;
    }

    /**
     * Sets the options the profile lists, through {@code set} (which is told the option and the index of the value in its list).
     *
     * @return what could not be set (an option the pack does not have, or a value it does not offer), one line each; empty when all of it was
     */
    public static List<String> apply(final List<PackOption> options, final Map<String, Map<String, String>> profiles, final String name,
                                     final java.util.function.ObjIntConsumer<PackOption> set) {
        List<String> problems = new ArrayList<>();
        Map<String, String> values = profiles.get(name);
        if (values == null) {
            problems.add("there is no profile " + name);
            return problems;
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            PackOption option = option(options, value.getKey());
            if (option == null) {
                problems.add("profile " + name + " sets " + value.getKey() + ", which the pack has no option for");
                continue;
            }
            int index = index(option, value.getValue());
            if (index < 0) {
                problems.add("profile " + name + " sets " + value.getKey() + " to " + value.getValue() + ", which its options do not offer (" + String.join(" ", option.values()) + ")");
                continue;
            }
            set.accept(option, index);
        }
        return problems;
    }
}
