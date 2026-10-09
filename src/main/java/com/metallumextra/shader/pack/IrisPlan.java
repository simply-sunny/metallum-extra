package com.metallumextra.shader.pack;

import com.mojang.blaze3d.GpuFormat;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a standard pack's programs ask for, worked out once when the pack is loaded: which programs run in which stage, the
 * buffers each reads and writes, and how each buffer is stored and cleared. It holds no GPU objects, so every rule in it
 * can be tested without a game.
 * <p>
 * The rules are Iris's: a program's {@code /* RENDERTARGETS: 0,3 *}{@code /} (or {@code DRAWBUFFERS}) says which buffers its
 * fragment outputs are written to, in order; without either, outputs are written to buffers 0, 1, 2 ... as far as the
 * program declares outputs. Programs of {@code begin}, {@code prepare}, {@code deferred} and {@code composite} read the
 * main texture of each buffer and write the alternate one, and the two are then swapped, unless the pack switches that
 * off with {@code flip.<program>.<buffer>=false}. {@code colortexNFormat}, {@code colortexNClear} and
 * {@code colortexNClearColor} constants in the programs set a buffer's format and clearing.
 */
public final class IrisPlan {
    public static final int COLOR_BUFFERS = 16;
    /** Metal draws into at most this many buffers in one pass. */
    public static final int MAX_OUTPUTS = 8;

    /** How a buffer is stored, and what it is cleared to at the start of every frame (never, when {@code clear} is false). */
    public record Buffer(int index, GpuFormat format, boolean clear, float @Nullable [] clearColor) {
        /** The color to clear to; {@code fog} stands in for the default of buffer 0. */
        public float[] colorToClear(final float[] fog) {
            if (clearColor != null) return clearColor;
            if (index == 0) return fog;
            return index == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
        }
    }

    /** One thing a program samples: a color buffer, or one of the depth textures. */
    public record Input(String name, int colorBuffer, int depthTexture) {
    }

    /**
     * One program in the order it runs.
     *
     * @param writes the buffers the fragment outputs go to; empty for {@code final}, which draws to the screen
     * @param inPlace per write: the buffer's flip is switched off, so the program draws into the texture that is read
     */
    public record Step(ProgramSet.Program program, int[] writes, boolean[] inPlace, List<Input> inputs) {
        public boolean reads(final int colorBuffer) {
            for (Input input : inputs) {
                if (input.colorBuffer == colorBuffer) return true;
            }
            return false;
        }
    }

    private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+?)\\s*\\*/");
    private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9]+)\\s*\\*/");
    private static final Pattern OUTPUT_LOCATION = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*(?:flat\\s+)?out\\b");
    private static final Pattern OUTPUT_NAMED = Pattern.compile("\\bout\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?vec4\\s+outColor(\\d+)\\b");
    private static final Pattern FORMAT = Pattern.compile("const\\s+int\\s+colortex(\\d+)Format\\s*=\\s*(\\w+)\\s*;");
    private static final Pattern CLEAR = Pattern.compile("const\\s+bool\\s+colortex(\\d+)Clear\\s*=\\s*(true|false)\\s*;");
    private static final Pattern CLEAR_COLOR = Pattern.compile("const\\s+vec4\\s+colortex(\\d+)ClearColor\\s*=\\s*vec4\\s*\\(([^)]*)\\)\\s*;");
    private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");

    /** The names OptiFine's older packs use for the first buffers. */
    private static final Map<String, Integer> OLD_NAMES = Map.of("gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3,
            "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);

    private final List<List<Step>> stages = new ArrayList<>();
    private final Map<Integer, Buffer> buffers = new TreeMap<>();
    private final boolean[] depthUsed = new boolean[3];
    private final List<String> notes = new ArrayList<>();
    private final ProgramSet programs;

    private IrisPlan(final ProgramSet programs) {
        this.programs = programs;
        for (int i = 0; i < ProgramSet.Stage.values().length; i++) stages.add(new ArrayList<>());
    }

    /** The programs of a stage, in the order they run in. */
    public List<Step> steps(final ProgramSet.Stage stage) {
        return stages.get(stage.ordinal());
    }

    /** The {@code final} program, or null when the pack has none. */
    public @Nullable Step finalStep() {
        List<Step> list = steps(ProgramSet.Stage.FINAL);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Every color buffer the programs touch, and how it is stored; nothing else is allocated. */
    public Map<Integer, Buffer> buffers() {
        return buffers;
    }

    /** Whether any program samples {@code depthtex<n>} (0: everything, 1: without translucent terrain, 2: without the hand too). */
    public boolean usesDepth(final int n) {
        return depthUsed[n];
    }

    /** Whether any program runs after the world has been drawn, which is what makes the world's image a buffer. */
    public boolean afterWorld() {
        return !steps(ProgramSet.Stage.DEFERRED).isEmpty() || !steps(ProgramSet.Stage.COMPOSITE).isEmpty() || finalStep() != null;
    }

    /** What the pack has that this version does not run, and approximations made, for the log. */
    public List<String> notes() {
        return notes;
    }

    public ProgramSet programs() {
        return programs;
    }

    public int stepCount() {
        int count = 0;
        for (List<Step> list : stages) count += list.size();
        return count;
    }

    /** @throws PackException if a program asks for something that cannot be provided, saying what */
    public static IrisPlan build(final ShaderPack pack, final @Nullable String dimension) throws PackException {
        ProgramSet set = ProgramSet.discover(pack, dimension);
        IrisPlan plan = new IrisPlan(set);
        for (String name : set.unsupported()) plan.notes.add(name + " is not run yet");

        Map<String, String> fragments = new TreeMap<>();
        for (ProgramSet.Program program : set.runnable()) {
            try {
                String vertex = pack.load(program.vertex());
                String fragment = pack.load(program.fragment());
                fragments.put(program.name(), fragment);
                plan.add(set, program, vertex, fragment);
            } catch (IllegalStateException e) {
                throw new PackException(e.getMessage(), e);
            }
        }
        plan.settleBuffers(fragments);
        return plan;
    }

    private void add(final ProgramSet set, final ProgramSet.Program program, final String vertex, final String fragment) throws PackException {
        List<Input> inputs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sampler : ProgramSet.samplers(vertex + "\n" + fragment)) {
            if (!seen.add(sampler)) continue;
            inputs.add(input(program, sampler));
        }
        int[] writes = program.stage() == ProgramSet.Stage.FINAL ? new int[0] : writes(program, fragment);
        Set<Integer> flipsOff = set.flipsOff(program.name());
        boolean[] inPlace = new boolean[writes.length];
        for (int i = 0; i < writes.length; i++) {
            inPlace[i] = flipsOff.contains(writes[i]);
            for (Input input : inputs) {
                if (input.colorBuffer == writes[i] && inPlace[i]) {
                    throw new PackException(program.name() + " reads colortex" + writes[i] + " while drawing into it (flip is off), which Metal does not allow");
                }
            }
            buffers.putIfAbsent(writes[i], null);
        }
        for (Input input : inputs) {
            if (input.colorBuffer >= 0) buffers.putIfAbsent(input.colorBuffer, null);
            if (input.depthTexture >= 0) depthUsed[input.depthTexture] = true;
        }
        stages.get(program.stage().ordinal()).add(new Step(program, writes, inPlace, List.copyOf(inputs)));
    }

    private static Input input(final ProgramSet.Program program, final String sampler) throws PackException {
        if (OLD_NAMES.containsKey(sampler)) return new Input(sampler, OLD_NAMES.get(sampler), -1);
        if (sampler.equals("gdepthtex")) return new Input(sampler, -1, 0);
        Matcher match = COLORTEX.matcher(sampler);
        if (match.matches()) {
            int index = Integer.parseInt(match.group(1));
            if (index >= COLOR_BUFFERS) throw new PackException(program.name() + " reads " + sampler + ", but there are only colortex0 to colortex" + (COLOR_BUFFERS - 1));
            return new Input(sampler, index, -1);
        }
        if (sampler.matches("depthtex[012]")) return new Input(sampler, -1, sampler.charAt(8) - '0');
        throw new PackException(program.name() + " reads " + sampler + ", which this version cannot provide yet");
    }

    /** The buffers a program's fragment outputs go to, in the order of the outputs. */
    private static int[] writes(final ProgramSet.Program program, final String fragment) throws PackException {
        int[] list = declared(fragment);
        if (list == null) {
            // Nothing said: the buffers in order, as many as the program has outputs.
            int outputs = Math.max(1, outputCount(fragment));
            list = new int[outputs];
            for (int i = 0; i < outputs; i++) list[i] = i;
        }
        if (list.length > MAX_OUTPUTS) throw new PackException(program.name() + " writes " + list.length + " buffers; at most " + MAX_OUTPUTS + " at once");
        Set<Integer> unique = new HashSet<>();
        for (int index : list) {
            if (index < 0 || index >= COLOR_BUFFERS) throw new PackException(program.name() + " writes colortex" + index + ", but there are only colortex0 to colortex" + (COLOR_BUFFERS - 1));
            if (!unique.add(index)) throw new PackException(program.name() + " writes colortex" + index + " twice");
        }
        return list;
    }

    /** The buffers named by the program's {@code RENDERTARGETS} or {@code DRAWBUFFERS} comment, or null if it has neither. */
    static int @Nullable [] declared(final String fragment) {
        Matcher targets = RENDERTARGETS.matcher(fragment);
        if (targets.find()) {
            String[] parts = targets.group(1).split("\\s*,\\s*");
            int[] list = new int[parts.length];
            for (int i = 0; i < parts.length; i++) list[i] = Integer.parseInt(parts[i].strip());
            return list;
        }
        Matcher draw = DRAWBUFFERS.matcher(fragment);
        if (draw.find()) {
            String digits = draw.group(1);
            int[] list = new int[digits.length()];
            for (int i = 0; i < digits.length(); i++) list[i] = digits.charAt(i) - '0';
            return list;
        }
        return null;
    }

    /** How many fragment outputs the program declares: one more than the highest location. */
    static int outputCount(final String fragment) {
        int most = -1;
        for (Pattern pattern : List.of(OUTPUT_LOCATION, OUTPUT_NAMED)) {
            Matcher match = pattern.matcher(fragment);
            while (match.find()) most = Math.max(most, Integer.parseInt(match.group(1)));
        }
        return most + 1;
    }

    private void settleBuffers(final Map<String, String> fragments) throws PackException {
        Map<Integer, GpuFormat> formats = new TreeMap<>();
        Map<Integer, Boolean> clears = new TreeMap<>();
        Map<Integer, float[]> colors = new TreeMap<>();
        for (Map.Entry<String, String> source : fragments.entrySet()) {
            String text = source.getValue();
            Matcher match = FORMAT.matcher(text);
            while (match.find()) formats.put(Integer.parseInt(match.group(1)), format(source.getKey(), Integer.parseInt(match.group(1)), match.group(2)));
            match = CLEAR.matcher(text);
            while (match.find()) clears.put(Integer.parseInt(match.group(1)), Boolean.parseBoolean(match.group(2)));
            match = CLEAR_COLOR.matcher(text);
            while (match.find()) colors.put(Integer.parseInt(match.group(1)), color(source.getKey(), match.group(2)));
        }
        for (int index : new ArrayList<>(buffers.keySet())) {
            buffers.put(index, new Buffer(index, formats.getOrDefault(index, GpuFormat.RGBA8_UNORM), clears.getOrDefault(index, true), colors.get(index)));
        }
        // The world's image is buffer 0 whenever anything runs after the world has been drawn.
        if (afterWorld() && !buffers.containsKey(0)) {
            buffers.put(0, new Buffer(0, formats.getOrDefault(0, GpuFormat.RGBA8_UNORM), clears.getOrDefault(0, true), colors.get(0)));
        }
    }

    private GpuFormat format(final String program, final int buffer, final String name) throws PackException {
        GpuFormat format = switch (name) {
            case "R8" -> GpuFormat.R8_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            case "RGBA8", "RGB8" -> GpuFormat.RGBA8_UNORM;
            case "R16" -> GpuFormat.R16_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RGBA16", "RGB16" -> GpuFormat.RGBA16_UNORM;
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "RGBA16F", "RGB16F", "R11F_G11F_B10F", "RGB10_A2" -> GpuFormat.RGBA16_FLOAT;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGBA32F", "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            default -> null;
        };
        if (format == null) throw new PackException(program + " gives colortex" + buffer + " the format " + name + ", which this version does not support");
        if (name.equals("RGB8") || name.equals("RGB16") || name.equals("RGB16F") || name.equals("RGB32F") || name.equals("R11F_G11F_B10F") || name.equals("RGB10_A2")) {
            notes.add("colortex" + buffer + " is " + name + "; stored with four channels (" + format.name() + ") because Metal has no three-channel or packed formats here");
        }
        return format;
    }

    private static float[] color(final String program, final String components) throws PackException {
        String[] parts = components.split(",");
        if (parts.length != 4) throw new PackException(program + ": a clear color needs vec4(r, g, b, a)");
        float[] result = new float[4];
        try {
            for (int i = 0; i < 4; i++) result[i] = Float.parseFloat(parts[i].strip().replaceAll("[fF]$", ""));
        } catch (NumberFormatException e) {
            throw new PackException(program + ": a clear color must be plain numbers, not '" + components + "'");
        }
        return result;
    }
}
