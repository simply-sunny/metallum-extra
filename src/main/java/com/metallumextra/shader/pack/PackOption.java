package com.metallumextra.shader.pack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A setting a pack offers, declared in its {@code pack.json}. The chosen value reaches the shaders as
 * {@code #define OPTION_<ID> <index>}, where the index counts from 0 in the order of {@code values}. A toggle is a
 * choice between {@code Off} (0) and {@code On} (1).
 */
public record PackOption(String id, String label, List<String> values, int defaultIndex, boolean toggle) {
    static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    static final int MAX_VALUES = 16;

    /** The name of the define the shaders read. */
    public String define() {
        return "OPTION_" + id;
    }

    /** The index of {@code value}, or the default if the pack has no such value (it may have been renamed since it was saved). */
    public int indexOf(final String value) {
        int index = values.indexOf(value);
        return index < 0 ? defaultIndex : index;
    }
}
