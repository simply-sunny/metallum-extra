package com.metallumextra.shader;

import com.metallumextra.shader.pack.ProgramSet;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a program's geometry stage did, for the programs drawn without it. Metal has no geometry stage, so a program that has one is drawn by its vertex and
 * fragment stages alone, and the two things its geometry stage did are put in their place:
 * <ul>
 * <li>it passed the vertex stage's outputs on under the names the fragment stage reads: {@code v_color} becomes {@code color};</li>
 * <li>it computed a value for each triangle, which is constant in the program here: the sky's {@code isMoon} (set by the stage the game draws the sun or the
 * moon with, since each is drawn with its own pipeline), and the terrain's {@code textureResolution} (the texel size of a face a block across, which is the
 * vanilla sprite size; the pack's {@code TEXTURE_RESOLUTION} option is used when it is set).</li>
 * </ul>
 */
public final class GeometryStage {
    /** What {@code textureResolution} is when the pack leaves {@code TEXTURE_RESOLUTION} at 0: the texel size of a vanilla sprite. */
    private static final String AUTO_TEXTURE_RESOLUTION = "16.0";

    private static final Pattern VERTEX_OUTPUT = Pattern.compile(
            "(?m)\\bout\\s+(?:(?:highp|mediump|lowp)\\s+)?(?:float|int|uint|vec[234]|ivec[234]|uvec[234]|mat[234]|mat[234]x[234])\\s+v_(\\w+)\\s*;");
    private static final Pattern IS_MOON = Pattern.compile("(?m)^[ \\t]*(?:flat[ \\t]+)?in[ \\t]+float[ \\t]+isMoon[ \\t]*;[ \\t]*$");
    private static final Pattern TEXTURE_RESOLUTION = Pattern.compile("(?m)^[ \\t]*(?:flat[ \\t]+)?in[ \\t]+float[ \\t]+textureResolution[ \\t]*;[ \\t]*$");

    private GeometryStage() {
    }

    /** The vertex stage of a program without its geometry stage: its outputs are the names the geometry stage passed on. */
    public static String vertex(final String source, final ProgramSet.Program program) {
        if (!program.geometry()) return source;
        Set<String> names = new TreeSet<>();
        Matcher output = VERTEX_OUTPUT.matcher(source);
        while (output.find()) names.add(output.group(1));
        if (!names.isEmpty()) Substitutions.note("the geometry stage passed " + names + " on to the fragment stage by name (the stage is not run)");
        String text = source;
        for (String name : names) {
            // A local of the vertex stage may have the name the output takes (the terrain has a local viewPos): the local is renamed first.
            text = text.replaceAll("\\b" + name + "\\b", "mx_vs_" + name);
            text = text.replaceAll("\\bv_" + name + "\\b", name);
        }
        return text;
    }

    /**
     * The fragment stage of a program without its geometry stage: the values the stage computed per triangle are constants.
     *
     * @param stage what the game is drawing on the sky ({@link IrisWorld#skyStage}), 0 for none
     */
    public static String fragment(final String source, final ProgramSet.Program program, final int stage) {
        if (!program.geometry()) return source;
        String text = source;
        if (IS_MOON.matcher(text).find()) {
            Substitutions.note("the geometry stage set isMoon per triangle: it is a constant for the stage the game draws with");
            text = IS_MOON.matcher(text).replaceAll("const float isMoon = " + (stage == IrisWorld.STAGE_MOON ? "1.0" : "0.0") + ";");
        }
        if (TEXTURE_RESOLUTION.matcher(text).find()) {
            Substitutions.note("the geometry stage set textureResolution per triangle: it is the vanilla sprite size (16) unless TEXTURE_RESOLUTION is set");
            text = TEXTURE_RESOLUTION.matcher(text).replaceAll("const float textureResolution = (TEXTURE_RESOLUTION == 0 ? " + AUTO_TEXTURE_RESOLUTION
                    + " : float(TEXTURE_RESOLUTION));");
        }
        return text;
    }
}
