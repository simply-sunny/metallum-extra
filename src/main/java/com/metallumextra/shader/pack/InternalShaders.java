package com.metallumextra.shader.pack;

import com.metallumextra.MetallumExtra;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The few shaders that belong to the mod itself and to no pack: the glue between the game's pipelines and a pack's programs. */
public final class InternalShaders {
    private static final String ROOT = "/assets/metallum-extra/shaders/internal/";

    private InternalShaders() {
    }

    /** The text of a file in {@code shaders/internal/}, or null. */
    public static @Nullable String read(final String name) {
        try (InputStream in = InternalShaders.class.getResourceAsStream(ROOT + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MetallumExtra.LOGGER.error("[Metallum Extra] Could not read shader {}", name, e);
            return null;
        }
    }
}
