package com.metallumextra.shader.pack;

import java.util.List;

/**
 * A setting a pack offers, found in its shader files (see {@link StandardOptions}). A toggle is a choice between {@code Off} (0) and {@code On} (1).
 * What the player chose is written into the shader text as the pack is read.
 */
public record PackOption(String id, String label, List<String> values, int defaultIndex, boolean toggle) {
    /** The index of {@code value}, or the default if the pack has no such value (it may have been renamed since it was saved). */
    public int indexOf(final String value) {
        int index = values.indexOf(value);
        return index < 0 ? defaultIndex : index;
    }
}
