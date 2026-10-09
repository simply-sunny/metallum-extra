package com.metallumextra.shader.pack;

import com.metallumextra.shader.Shaders;

import java.util.List;

/**
 * The built-in pack's options, for the mod's own settings screen and keys. They are the pack's options like any other pack's (see
 * {@link StandardOptions}), kept in {@link PackOptions} under the pack's name; there is no second copy in the mod's config.
 */
public final class BuiltinOptions {
    private static final BuiltinPack PACK = new BuiltinPack();

    private BuiltinOptions() {
    }

    private static PackOption find(final String id) {
        for (PackOption option : PACK.options()) {
            if (option.id().equals(id)) return option;
        }
        throw new IllegalArgumentException("The built-in pack has no option " + id);
    }

    public static boolean on(final String id) {
        return PackOptions.get(BuiltinPack.NAME, find(id)) == 1;
    }

    public static String value(final String id) {
        PackOption option = find(id);
        return option.values().get(PackOptions.get(BuiltinPack.NAME, option));
    }

    public static List<String> values(final String id) {
        return find(id).values();
    }

    public static void set(final String id, final boolean on) {
        setIndex(id, on ? 1 : 0);
    }

    public static void setValue(final String id, final String value) {
        setIndex(id, find(id).values().indexOf(value));
    }

    private static void setIndex(final String id, final int index) {
        PackOption option = find(id);
        if (index < 0 || index == PackOptions.get(BuiltinPack.NAME, option)) return;
        PackOptions.set(BuiltinPack.NAME, option, index);
        // Only the pack in use is compiled; another reads its options when it is chosen.
        if (BuiltinPack.NAME.equals(PackManager.active().name())) Shaders.packOptionsChanged();
    }
}
