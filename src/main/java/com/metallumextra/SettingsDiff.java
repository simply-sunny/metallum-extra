package com.metallumextra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The lines that say what changed between two settings snapshots, one per key, in key order. */
public final class SettingsDiff {
    private SettingsDiff() {
    }

    public static List<String> diff(final Map<String, String> before, final Map<String, String> after) {
        List<String> lines = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            String was = before.get(key);
            String now = after.get(key);
            if (was == null) {
                lines.add(key + ": (none) -> " + now);
            } else if (now == null) {
                lines.add(key + ": " + was + " -> (removed)");
            } else if (!was.equals(now)) {
                lines.add(key + ": " + was + " -> " + now);
            }
        }
        return lines;
    }
}
