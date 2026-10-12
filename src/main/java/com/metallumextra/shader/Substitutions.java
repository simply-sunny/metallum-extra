package com.metallumextra.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where the translation put something in place of what a pack wrote, without failing: a varying the other stage never writes (it reads zero), a sampler the
 * pipeline has no slot for (it reads white), a geometry stage replaced by its effect. None of these fail the compile, so the compile check collects them
 * here (see {@code ShaderCompileCheck}). Nothing is recorded in the game: {@link #note} does nothing until {@link #record} turns it on.
 */
public final class Substitutions {
    private static final Map<String, List<String>> FOUND = new TreeMap<>();
    private static boolean recording;
    private static String context = "";

    private Substitutions() {
    }

    /** Starts (or stops, and clears) recording. */
    public static synchronized void record(final boolean on) {
        recording = on;
        FOUND.clear();
        context = "";
    }

    /** The program the notes that follow belong to. */
    public static synchronized void context(final String program) {
        context = program;
    }

    /** Notes one substitution for the program in {@link #context}. */
    public static synchronized void note(final String what) {
        if (!recording) return;
        List<String> notes = FOUND.computeIfAbsent(context, k -> new ArrayList<>());
        if (!notes.contains(what)) notes.add(what);
    }

    /** The substitutions found, by program, in the order the programs were checked. */
    public static synchronized Map<String, List<String>> found() {
        return new TreeMap<>(FOUND);
    }
}
