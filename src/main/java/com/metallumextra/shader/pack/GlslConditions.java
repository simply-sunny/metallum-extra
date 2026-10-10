package com.metallumextra.shader.pack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the parts of a shader out that the preprocessor would not compile, so that what the loader looks for in it (samplers, vertex inputs,
 * outputs) is only what is really there. The text is not otherwise changed, and the lines stay (blank) so that numbers in messages stay right.
 * <p>
 * Only what can be decided from the file is decided: a condition on a name that no line defines and that is not an option (the game defines some
 * for the pipeline it compiles a program for, such as {@code EMISSIVE}) is left open, and both of its sides are kept.
 */
public final class GlslConditions {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(ifdef|ifndef|if|elif|else|endif|define|undef)\\b(.*)$");
    private static final Pattern DEFINED = Pattern.compile("defined\\s*\\(\\s*(\\w+)\\s*\\)|defined\\s+(\\w+)");

    /** The names the game's pipelines define after the pack's text is read (the vanilla shaders test these), so the text cannot decide them. */
    private static final Set<String> PIPELINE = Set.of("ALPHA_CUTOUT", "APPLY_TEXTURE_MATRIX", "DISSOLVE", "EMISSIVE", "IS_GRAYSCALE", "IS_GUI", "IS_SEE_THROUGH",
            "NO_CARDINAL_LIGHTING", "NO_OVERLAY", "PER_FACE_LIGHTING");

    private GlslConditions() {
    }

    /** Every name {@link #open} is true for that can be listed (the prefixes cannot, and are matched in {@link #open}). */
    private static final Set<String> PIPELINE_NAMES = new java.util.AbstractSet<>() {
        @Override
        public boolean contains(final Object name) {
            return name instanceof String text && open(text);
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return PIPELINE.iterator();
        }

        @Override
        public int size() {
            return PIPELINE.size();
        }
    };

    private static boolean open(final String name) {
        return PIPELINE.contains(name) || name.startsWith("MX_") || name.startsWith("USE_");
    }

    /** One level of nesting: whether its current branch is kept, and whether any earlier branch of it was taken (so an else is not). */
    private static final class Level {
        boolean parentKept;
        boolean kept;
        boolean taken;
        boolean open;
    }

    private static final Pattern DEFINE_BODY = Pattern.compile("(\\w+)\\s*(.*?)\\s*(?://.*)?$");

    /**
     * For analysis (which samplers and inputs a program really has): the text with every directive blank and the lines the conditions rule out blank too. A
     * condition that cannot be decided keeps both of its sides.
     *
     * @param options the names of the pack's options: each is known, and defined or not as the text says
     */
    public static String strip(final String source, final Set<String> options) {
        return process(source, options, false);
    }

    /**
     * For compiling: the text with the lines that decided conditions rule out blank, and the directives of those conditions blank. A condition that cannot
     * be decided (a name the game defines when it compiles) is left exactly as written, with both of its sides, for the compiler's preprocessor to decide.
     */
    public static String decide(final String source, final Set<String> options) {
        return process(source, options, true);
    }

    private static String process(final String source, final Set<String> options, final boolean keepOpen) {
        Map<String, String> defined = new HashMap<>();
        String[] lines = source.split("\n", -1);
        // The directive on each line, and the condition it belongs to (null for a line that is not part of one).
        Level[] owner = new Level[lines.length];
        boolean[] directive = new boolean[lines.length];
        boolean[] keptLine = new boolean[lines.length];
        Deque<Level> stack = new ArrayDeque<>();
        boolean kept = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher match = line.indexOf('#') < 0 ? null : DIRECTIVE.matcher(line);
            boolean isDirective = match != null && match.matches();
            String kind = isDirective ? match.group(1) : "";
            String rest = isDirective ? match.group(2).strip() : "";
            directive[i] = isDirective && !kind.equals("define") && !kind.equals("undef");
            switch (kind) {
                case "ifdef", "ifndef", "if" -> {
                    Level level = new Level();
                    level.parentKept = kept;
                    Boolean value = condition(kind, rest, defined, options);
                    level.open = value == null;
                    level.kept = value == null || value;
                    level.taken = value != null && value;
                    stack.push(level);
                    owner[i] = level;
                    kept = level.parentKept && level.kept;
                }
                case "elif" -> {
                    Level level = stack.peek();
                    owner[i] = level;
                    if (level != null) {
                        Boolean value = level.taken ? Boolean.FALSE : condition("if", rest, defined, options);
                        level.open |= value == null;
                        level.kept = value == null || value;
                        level.taken |= value != null && value;
                        kept = level.parentKept && level.kept;
                    }
                }
                case "else" -> {
                    Level level = stack.peek();
                    owner[i] = level;
                    if (level != null) {
                        level.kept = level.open || !level.taken;
                        kept = level.parentKept && level.kept;
                    }
                }
                case "endif" -> {
                    Level level = stack.poll();
                    owner[i] = level;
                    if (level != null) kept = level.parentKept;
                }
                case "define" -> {
                    if (kept) {
                        Matcher d = DEFINE_BODY.matcher(rest);
                        if (d.matches()) defined.put(d.group(1), d.group(2));
                    }
                }
                case "undef" -> {
                    if (kept) defined.remove(rest);
                }
                default -> {
                }
            }
            keptLine[i] = kept;
        }
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < lines.length; i++) {
            if (directive[i]) {
                // A condition the text cannot decide stays in the text, if the code around it does.
                Level level = owner[i];
                if (keepOpen && level != null && level.open && level.parentKept) out.append(lines[i]);
            } else if (keptLine[i]) {
                out.append(lines[i]);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** True or false when the condition can be decided here, null when it depends on something the file does not say. */
    private static Boolean condition(final String kind, final String text, final Map<String, String> defined, final Set<String> known) {
        switch (kind) {
            case "ifdef":
                return open(text) ? null : Boolean.valueOf(defined.containsKey(text));
            case "ifndef":
                return open(text) ? null : Boolean.valueOf(!defined.containsKey(text));
            default:
                break;
        }
        try {
            return PreprocessorExpression.evaluate(text.replaceAll("//.*$", "").replaceAll("/\\*.*?\\*/", " ").strip(), defined, PIPELINE_NAMES);
        } catch (PreprocessorExpression.Open | PackException e) {
            return null;
        }
    }
}
