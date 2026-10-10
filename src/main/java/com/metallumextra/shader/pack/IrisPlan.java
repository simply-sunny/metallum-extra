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
    public record Buffer(int index, GpuFormat format, boolean clear, float @Nullable [] clearColor, float scaleX, float scaleY, int width, int height) {
        /** A buffer as big as the screen. */
        public Buffer(final int index, final GpuFormat format, final boolean clear, final float @Nullable [] clearColor) {
            this(index, format, clear, clearColor, 1.0F, 1.0F, 0, 0);
        }

        /** Its size on a screen of this size. */
        public int widthOn(final int screenWidth) {
            return width > 0 ? width : Math.max(1, (int) (screenWidth * scaleX));
        }

        public int heightOn(final int screenHeight) {
            return height > 0 ? height : Math.max(1, (int) (screenHeight * scaleY));
        }

        public boolean fullSize() {
            return width == 0 && scaleX == 1.0F && scaleY == 1.0F;
        }

        /** The color to clear to; {@code fog} stands in for the default of buffer 0. */
        public float[] colorToClear(final float[] fog) {
            if (clearColor != null) return clearColor;
            if (index == 0) return fog;
            return index == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
        }
    }

    /** One thing a program samples: a color buffer, or one of the depth textures. */
    public record Input(String name, int colorBuffer, int depthTexture, @Nullable String texture) {
        public Input(final String name, final int colorBuffer, final int depthTexture) {
            this(name, colorBuffer, depthTexture, null);
        }
    }

    /** {@link Input#colorBuffer} of a picture the pack supplies ({@link Input#texture} is its path). */
    public static final int CUSTOM = -5;

    /**
     * One program in the order it runs.
     *
     * @param writes the buffers the fragment outputs go to; empty for {@code final}, which draws to the screen
     * @param inPlace per write: the buffer's flip is switched off, so the program draws into the texture that is read
     * @param uniforms the standard uniforms the program declares, in the order of {@link IrisUniforms#ALL}; they reach it in one block
     */
    public record Step(ProgramSet.Program program, int[] writes, boolean[] inPlace, List<Input> inputs, List<String> uniforms) {
        public boolean reads(final int colorBuffer) {
            for (Input input : inputs) {
                if (input.colorBuffer == colorBuffer) return true;
            }
            return false;
        }
    }

    /** The kinds of terrain Sodium draws, each with its own program (the game draws them in this order). */
    public enum Layer {
        SOLID, CUTOUT, TRANSLUCENT
    }

    /** The program that draws a layer of terrain: the layer's own, then the ones Iris falls back to, in Iris's order. */
    private static final Map<Layer, List<String>> TERRAIN_CHAIN = Map.of(
            Layer.SOLID, List.of("gbuffers_terrain_solid", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
            Layer.CUTOUT, List.of("gbuffers_terrain_cutout", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
            Layer.TRANSLUCENT, List.of("gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"));

    /** The program that draws a layer of terrain into the shadow map; translucent terrain (water, glass) casts no shadow. */
    private static final Map<Layer, List<String>> SHADOW_CHAIN = Map.of(
            Layer.SOLID, List.of("shadow_solid", "shadow"), Layer.CUTOUT, List.of("shadow_cutout", "shadow"), Layer.TRANSLUCENT, List.of());

    /** The kinds of things in the world the game draws with its own pipelines, each with the programs Iris tries, in order. */
    public enum Use {
        ENTITY("gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        ENTITY_TRANSLUCENT("gbuffers_entities_translucent", "gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        BLOCK("gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        BLOCK_TRANSLUCENT("gbuffers_block_translucent", "gbuffers_block", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        PARTICLES("gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        PARTICLES_TRANSLUCENT("gbuffers_particles_translucent", "gbuffers_particles", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        WEATHER("gbuffers_weather", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        /** The held item and the arm, drawn after everything else; and the translucent parts of them. */
        HAND("gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        HAND_WATER("gbuffers_hand_water", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
        /** The glowing parts of mobs (eyes of spiders and endermen, a charged creeper's glow). */
        EYES("gbuffers_spidereyes", "gbuffers_textured", "gbuffers_basic"),
        CLOUDS("gbuffers_clouds", "gbuffers_textured", "gbuffers_basic"),
        /** The sky behind everything, drawn over the whole screen in place of the game's disc, sunrise fan and dark disc. */
        SKY("gbuffers_skybasic"),
        /** Mobs, items and block entities seen from the light, and moving blocks. */
        SHADOW_ENTITY("shadow_entities", "shadow"),
        SHADOW_BLOCK("shadow_block", "shadow");

        private final List<String> chain;

        Use(final String... chain) {
            this.chain = List.of(chain);
        }

        public List<String> chain() {
            return chain;
        }
    }

    /** The vertex inputs a terrain program may declare, with their types. Everything else Iris offers is refused by name. */
    static final Map<String, String> TERRAIN_INPUTS = Map.of("vaPosition", "vec3", "vaColor", "vec4", "vaUV0", "vec2", "vaUV2", "ivec2", "vaNormal", "vec3", "mc_Entity", "vec2", "mc_chunkFade", "float", "mc_midTexCoord", "vec2", "at_tangent", "vec4", "at_midBlock", "vec4");
    /** The vertex inputs a program for anything but terrain may declare: terrain's plus the overlay; mc_Entity is -1 there, as in Iris. */
    static final Map<String, String> WORLD_INPUTS = Map.ofEntries(Map.entry("vaPosition", "vec3"), Map.entry("vaColor", "vec4"), Map.entry("vaUV0", "vec2"), Map.entry("vaUV1", "ivec2"),
            Map.entry("vaUV2", "ivec2"), Map.entry("vaNormal", "vec3"), Map.entry("mc_Entity", "vec2"), Map.entry("mc_chunkFade", "float"), Map.entry("mc_midTexCoord", "vec2"),
            Map.entry("at_tangent", "vec4"), Map.entry("at_midBlock", "vec4"));
    private static final List<String> LATER_INPUTS = List.of();
    private static final Pattern VERTEX_INPUT = Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*;");
    /** The compatibility profile's names, which need the translation of the next step; until then they are named in the error. */
    private static final Pattern LEGACY = Pattern.compile("\\b(varying|gl_FragData|gl_FragColor|texture2D|texture2DLod|texture3D|gl_Vertex|gl_Normal|gl_Color|gl_MultiTexCoord\\d|ftransform"
            + "|gl_ModelViewMatrix|gl_ProjectionMatrix|gl_ModelViewProjectionMatrix|gl_NormalMatrix|gl_TextureMatrix|gl_ModelViewMatrixInverse|gl_ProjectionMatrixInverse|attribute)\\b");
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");
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
    private float sunPathRotation;
    private boolean translucentReadsColor;
    private boolean usesLightColors;
    private int shadowResolution = 1024;
    private float shadowDistance = 160.0F;
    private final Map<String, String> vertexSources = new TreeMap<>();
    private final Set<Integer> worldBuffers = new java.util.TreeSet<>();
    private final List<Input> worldSlots = new ArrayList<>();
    private boolean usesBlockIds;

    private IrisPlan(final ProgramSet programs) {
        this.programs = programs;
        for (int i = 0; i < ProgramSet.Stage.values().length; i++) stages.add(new ArrayList<>());
    }

    /**
     * The source without its {@code const int colortexNFormat = RGBA16F;} lines. They tell this class how to store a buffer; the format
     * names are not GLSL, and Iris does not pass them to the compiler either.
     */
    public static String withoutBufferFormats(final String source) {
        return FORMAT.matcher(source).replaceAll("");
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

    /** The program that draws this kind of thing, or null if the pack has none of the programs Iris would use (the game's own shader then draws it). */
    public @Nullable Step worldStep(final Use use) {
        for (String name : use.chain()) {
            Step step = step(name);
            if (step != null) return step;
        }
        return null;
    }

    /**
     * The color buffers (other than 0) that any program drawing the world writes. Every pass that draws the world has them attached, in the
     * attachment of the same number, so everything drawn into it agrees on where each output goes.
     */
    public Set<Integer> worldBuffers() {
        return worldBuffers;
    }

    /** The program that draws this layer of terrain, or null if the pack has none of the programs Iris would use (the game's own shader then draws it). */
    public @Nullable Step terrainStep(final Layer layer) {
        for (String name : TERRAIN_CHAIN.get(layer)) {
            Step step = step(name);
            if (step != null) return step;
        }
        return null;
    }

    /** The program of this name, whatever its stage; null if it does not run. */
    public @Nullable Step step(final String name) {
        for (List<Step> list : stages) {
            for (Step step : list) {
                if (step.program().name().equals(name)) return step;
            }
        }
        return null;
    }

    /** Whether the pack gives blocks ids of its own (block.properties), which terrain programs then read as {@code mc_Entity.x}. */
    public boolean usesBlockIds() {
        return usesBlockIds;
    }

    /** Most things the programs that draw the world can read besides the fixed ones (see {@link #worldSlots()}). */
    public static final int WORLD_SLOTS = 48;

    /**
     * What the programs that draw the world read apart from the atlas, light map, shadow map and the first buffer's copy: each distinct sampler name has a
     * slot, and the game's passes bind the slot's content as {@code MxWorld<slot>} (see {@code ShaderBindings}).
     */
    public List<Input> worldSlots() {
        return worldSlots;
    }

    /** The slot the world programs read this sampler through, or -1. */
    public int worldSlotOf(final String sampler) {
        for (int i = 0; i < worldSlots.size(); i++) {
            if (worldSlots.get(i).name().equals(sampler)) return i;
        }
        return -1;
    }

    private void slotFor(final Input input) throws PackException {
        if (worldSlotOf(input.name()) >= 0) return;
        if (worldSlots.size() >= WORLD_SLOTS) throw new PackException("the programs that draw the world read more than " + WORLD_SLOTS + " different samplers beyond the atlas and light map (" + input.name() + " is one too many)");
        worldSlots.add(input);
    }

    /** Whether a program reads the grid of block light colors, which then has to be kept up to date. */
    public boolean usesLightColors() {
        return usesLightColors;
    }

    /** Whether a program that draws translucent things reads {@code colortex0}, the world behind them. */
    public boolean translucentReadsColor() {
        return translucentReadsColor;
    }

    /** Whether the pack has a program that draws the shadow map. */
    public boolean hasShadow() {
        return !steps(ProgramSet.Stage.SHADOW).isEmpty();
    }

    /** The shadow map's width and height, from {@code const int shadowMapResolution}; Iris's default 1024 when the pack does not say. */
    public int shadowResolution() {
        return shadowResolution;
    }

    /** How far the shadow map reaches from the player, in blocks, from {@code const float shadowDistance}; Iris's default 160. */
    public float shadowDistance() {
        return shadowDistance;
    }

    /** The program that draws this layer of terrain into the shadow map, or null if the layer casts none. */
    public @Nullable Step shadowTerrainStep(final Layer layer) {
        for (String name : SHADOW_CHAIN.get(layer)) {
            Step step = step(name);
            if (step != null) return step;
        }
        return null;
    }

    /** The tilt of the sun's and moon's path in degrees, from {@code const float sunPathRotation} in the programs; 0 when they do not say. */
    public float sunPathRotation() {
        return sunPathRotation;
    }

    public int stepCount() {
        int count = 0;
        for (List<Step> list : stages) count += list.size();
        return count;
    }

    /** @throws PackException if a program asks for something that cannot be provided, saying what */
    public static IrisPlan build(final ShaderPack pack, final @Nullable String dimension) throws PackException {
        ProgramSet set = ProgramSet.discover(pack, dimension);
        // The programs may declare the pack's own uniforms, so they must be known before any program is read.
        IrisUniforms.useCustom(set.customUniforms(name -> 0.0).uniforms());
        IrisPlan plan = new IrisPlan(set);
        plan.usesBlockIds = !set.blockMappings().isEmpty();
        for (String name : set.unsupported()) plan.notes.add(name + " is not run yet");

        Map<String, String> fragments = new TreeMap<>();
        for (ProgramSet.Program program : set.runnable()) {
            try {
                String vertex = pack.load(program.vertex());
                String fragment = pack.load(program.fragment());
                fragments.put(program.name(), fragment);
                plan.findSunPath(vertex);
                plan.findSunPath(fragment);
                plan.add(set, program, vertex, fragment);
            } catch (IllegalStateException e) {
                throw new PackException(e.getMessage(), e);
            }
        }
        plan.settleBuffers(fragments);
        plan.validateUses();
        return plan;
    }

    private static final Pattern SUN_PATH = Pattern.compile("const\\s+float\\s+sunPathRotation\\s*=\\s*(-?\\d+(?:\\.\\d*)?)\\s*;");

    private static final Pattern SHADOW_RESOLUTION = Pattern.compile("const\\s+int\\s+shadowMapResolution\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern SHADOW_DISTANCE = Pattern.compile("const\\s+(?:float|int)\\s+shadowDistance\\s*=\\s*(\\d+(?:\\.\\d*)?)\\s*;");

    private void findSunPath(final String source) {
        Matcher match = SUN_PATH.matcher(source);
        if (match.find()) sunPathRotation = Float.parseFloat(match.group(1));
        match = SHADOW_RESOLUTION.matcher(source);
        if (match.find()) shadowResolution = Math.max(16, Math.min(8192, Integer.parseInt(match.group(1))));
        match = SHADOW_DISTANCE.matcher(source);
        if (match.find()) shadowDistance = Math.max(16.0F, Float.parseFloat(match.group(1)));
    }

    private void add(final ProgramSet set, final ProgramSet.Program program, final String vertexSource, final String fragmentSource) throws PackException {
        // What the program asks for is what the preprocessor would leave of it.
        Set<String> optionNames = new HashSet<>();
        for (PackOption option : set.options()) optionNames.add(option.id());
        String vertex = GlslConditions.strip(vertexSource, optionNames);
        String fragment = GlslConditions.strip(fragmentSource, optionNames);
        boolean shadow = program.stage() == ProgramSet.Stage.SHADOW;
        boolean gbuffers = program.stage() == ProgramSet.Stage.GBUFFERS || shadow;
        rejectLegacy(program, vertex, fragment);
        if (gbuffers) vertexSources.put(program.name(), vertex);
        List<Input> inputs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sampler : ProgramSet.samplers(vertex + "\n" + fragment)) {
            if (!seen.add(sampler)) continue;
            String custom = set.customTexture(program.stage(), sampler);
            // The resource pack's normal and specular maps: there are none yet (every surface is flat and not shiny), as in Iris without one.
            if (custom == null && sampler.equals("normals")) custom = "mx:flat_normals";
            if (custom == null && sampler.equals("specular")) custom = "mx:no_specular";
            if (custom != null) {
                if (!custom.startsWith("mx:") && set.pack().bytes(custom) == null) throw new PackException(program.name() + " reads " + sampler + ", which shaders.properties gives the picture " + custom + ", but the pack has no such PNG");
                Input made = new Input(sampler, CUSTOM, -1, custom);
                if (gbuffers) slotFor(made);
                inputs.add(made);
                continue;
            }
            Input made = gbuffers ? gbufferInput(program, sampler, shadow) : input(program, sampler);
            // The ones read through the game's passes (see ShaderBindings) that are not one of its fixed bindings get a slot of their own.
            if (gbuffers && made.colorBuffer >= 1) slotFor(made);
            inputs.add(made);
        }
        int[] writes = program.stage() == ProgramSet.Stage.FINAL ? new int[0] : writes(program, fragment);
        if (gbuffers && writes[0] != 0) {
            throw new PackException(program.name() + " writes colortex" + writes[0] + " first, but this version draws the world into colortex0, so the first output must go there");
        }
        Set<Integer> flipsOff = set.flipsOff(program.name());
        boolean[] inPlace = new boolean[writes.length];
        for (int i = 0; i < writes.length; i++) {
            // The programs that draw the world draw into the buffers' main textures, which the programs after them read: nothing flips.
            inPlace[i] = gbuffers || flipsOff.contains(writes[i]);
            for (Input input : inputs) {
                if (!gbuffers && input.colorBuffer == writes[i] && inPlace[i]) {
                    throw new PackException(program.name() + " reads colortex" + writes[i] + " while drawing into it (flip is off), which Metal does not allow");
                }
            }
            if (!shadow) buffers.putIfAbsent(writes[i], null);
        }
        for (Input input : inputs) {
            if (gbuffers && input.colorBuffer == 0 && !shadow && (program.name().equals("gbuffers_water") || program.name().equals("gbuffers_hand_water") || program.name().endsWith("_translucent"))) translucentReadsColor = true;
            if (input.colorBuffer == LIGHT_COLORS) usesLightColors = true;
            if (input.colorBuffer >= 0) buffers.putIfAbsent(input.colorBuffer, null);
            if (input.depthTexture >= 0) depthUsed[input.depthTexture] = true;
        }
        Set<String> both = new java.util.LinkedHashSet<>(IrisUniforms.declared(vertex));
        both.addAll(IrisUniforms.declared(fragment));
        List<String> uniforms = IrisUniforms.allUniforms().stream().map(IrisUniforms.Uniform::name).filter(both::contains).toList();
        if (!gbuffers) {
            for (IrisUniforms.Uniform uniform : IrisUniforms.allUniforms()) {
                if (uniform.gbuffers() && both.contains(uniform.name())) throw new PackException(program.name() + " declares " + uniform.name() + ", which only the programs that draw the world have");
            }
        }
        stages.get(program.stage().ordinal()).add(new Step(program, writes, inPlace, List.copyOf(inputs), uniforms));
    }

    /**
     * What a program that draws the world may sample: the block atlas, the light map and the shadow map; and, for the programs that draw
     * translucent things (water, glass), the world as it was before them: {@code colortex0} and the depth textures.
     */
    private static Input gbufferInput(final ProgramSet.Program program, final String sampler, final boolean shadow) throws PackException {
        if (sampler.equals("gtexture") || sampler.equals("lightmap")) return new Input(sampler, -1, -1);
        // The built-in shader pack's own: the colors of block light around the player, and the mask of a dissolving model.
        if (!shadow && sampler.equals("MxLightColors")) return new Input(sampler, LIGHT_COLORS, -1);
        if (sampler.equals("DissolveMaskSampler")) return new Input(sampler, -1, -1);
        if (sampler.equals("texture")) throw new PackException(program.name() + " names its atlas sampler 'texture', which is also a GLSL function; use gtexture");
        if (!shadow && sampler.matches("shadowtex[01]")) return new Input(sampler, SHADOW_DEPTH, -1);
        if (!shadow && sampler.equals("shadowcolor0")) return new Input(sampler, SHADOW_COLOR, -1);
        // Every program that draws the world may bind the depth of the world so far and the buffers the passes before it wrote, as in Iris.
        if (sampler.matches("depthtex[012]") || sampler.equals("gdepthtex")) return new Input(sampler, -1, 1);
        if (OLD_NAMES.containsKey(sampler)) return new Input(sampler, OLD_NAMES.get(sampler), -1);
        Matcher buffer = COLORTEX.matcher(sampler);
        if (buffer.matches()) {
            int index = Integer.parseInt(buffer.group(1));
            if (index >= COLOR_BUFFERS) throw new PackException(program.name() + " reads " + sampler + ", but there are only colortex0 to colortex" + (COLOR_BUFFERS - 1));
            return new Input(sampler, index, -1);
        }
        throw new PackException(program.name() + " reads " + sampler + ", which this version cannot provide to the programs that draw the world yet");
    }

    /** {@link Input#colorBuffer} of the shadow map's depth and of its color. */
    public static final int SHADOW_DEPTH = -2;
    public static final int SHADOW_COLOR = -3;
    /** The color grid of block light, which only the built-in shader pack reads. */
    public static final int LIGHT_COLORS = -4;

    /** The compatibility profile is named in the error until the next step translates it. */
    private static void rejectLegacy(final ProgramSet.Program program, final String vertex, final String fragment) throws PackException {
        for (String source : List.of(vertex, fragment)) {
            Matcher match = LEGACY.matcher(COMMENTS.matcher(source).replaceAll(""));
            if (match.find()) {
                throw new PackException(program.name() + " uses " + match.group(1) + ", from the old compatibility profile of GLSL, which this version cannot read yet (use #version 330 core with in/out)");
            }
        }
    }

    /** Terrain programs read Sodium's vertices through the Iris names; refuses the ones that cannot be provided. */
    private static void checkVertexInputs(final ProgramSet.Program program, final String vertex, final Map<String, String> allowed, final String use) throws PackException {
        Matcher match = VERTEX_INPUT.matcher(COMMENTS.matcher(vertex).replaceAll(""));
        while (match.find()) {
            String type = match.group(1);
            String name = match.group(2);
            String expected = allowed.get(name);
            if (expected == null) {
                if (LATER_INPUTS.contains(name)) throw new PackException(program.name() + " reads the vertex input " + name + ", which this version cannot provide yet");
                if (name.equals("mc_Entity")) throw new PackException(program.name() + " reads mc_Entity, which only the programs for terrain have (it draws " + use + ")");
                if (name.equals("vaUV1")) throw new PackException(program.name() + " reads vaUV1 (the overlay), which terrain does not have (it draws " + use + ")");
                throw new PackException(program.name() + " declares the vertex input " + name + ", which is not an Iris input this version knows");
            }
            if (!expected.equals(type)) throw new PackException(program.name() + " declares " + name + " as " + type + ", but it is a " + expected);
        }
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
        if (sampler.matches("shadowtex[01]")) return new Input(sampler, SHADOW_DEPTH, -1);
        if (sampler.equals("shadowcolor0")) return new Input(sampler, SHADOW_COLOR, -1);
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

    /**
     * Each kind of thing is drawn with the first program that exists in its chain, and that program must be able to read what
     * that kind of thing has: terrain has a block id and no overlay, everything else the reverse.
     */
    private void validateUses() throws PackException {
        for (Layer layer : Layer.values()) {
            Step step = terrainStep(layer);
            if (step != null) checkVertexInputs(step.program(), vertexSources.get(step.program().name()), TERRAIN_INPUTS, "terrain");
        }
        for (Layer layer : Layer.values()) {
            Step step = shadowTerrainStep(layer);
            if (step != null) checkVertexInputs(step.program(), vertexSources.get(step.program().name()), TERRAIN_INPUTS, "terrain in the shadow map");
        }
        for (Use use : Use.values()) {
            Step step = worldStep(use);
            if (step != null) checkVertexInputs(step.program(), vertexSources.get(step.program().name()), WORLD_INPUTS, use.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        }
        for (Step step : stages.get(ProgramSet.Stage.GBUFFERS.ordinal())) {
            for (int index : step.writes()) {
                if (index != 0) {
                    if (index >= MAX_OUTPUTS) throw new PackException(step.program().name() + " writes colortex" + index + ", but the programs that draw the world can only write colortex0 to colortex" + (MAX_OUTPUTS - 1));
                    worldBuffers.add(index);
                }
            }
        }
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
        // The world's image is buffer 0 whenever anything runs after the world has been drawn.
        if (afterWorld() && !buffers.containsKey(0)) buffers.put(0, null);
        for (int index : new ArrayList<>(buffers.keySet())) {
            float[] size = size(index);
            Buffer made = new Buffer(index, formats.getOrDefault(index, GpuFormat.RGBA8_UNORM), clears.getOrDefault(index, true), colors.get(index),
                    size == null ? 1.0F : size[0], size == null ? 1.0F : size[1], size == null ? 0 : (int) size[2], size == null ? 0 : (int) size[3]);
            if (index == 0 && !made.fullSize()) throw new PackException("colortex0 is the world's image, and cannot have a size of its own (size.buffer.colortex0)");
            buffers.put(index, made);
        }
        // A program draws into all its buffers at once, so they must be the same size.
        for (List<Step> stage : stages) {
            for (Step step : stage) {
                if (step.writes().length < 2) continue;
                Buffer first = buffers.get(step.writes()[0]);
                for (int index : step.writes()) {
                    Buffer other = buffers.get(index);
                    if (first != null && other != null && (first.scaleX() != other.scaleX() || first.scaleY() != other.scaleY() || first.width() != other.width() || first.height() != other.height())) {
                        throw new PackException(step.program().name() + " writes colortex" + step.writes()[0] + " and colortex" + index + ", which are different sizes");
                    }
                }
            }
        }
    }

    /**
     * The size {@code size.buffer.colortexN} gives a buffer: two numbers, each a share of the screen's width or height when below 16 (0.5 is half) and a number of
     * pixels otherwise. Returns {scaleX, scaleY, width, height}, or null when the pack says nothing.
     */
    private float @Nullable [] size(final int buffer) throws PackException {
        String value = programs.property("size.buffer.colortex" + buffer);
        if (value == null) return null;
        String[] parts = value.split("\\s+");
        if (parts.length != 2) throw new PackException("size.buffer.colortex" + buffer + " needs two numbers, width and height");
        try {
            float[] result = {1.0F, 1.0F, 0, 0};
            float x = Float.parseFloat(parts[0]), y = Float.parseFloat(parts[1]);
            if (x <= 0 || y <= 0) throw new PackException("size.buffer.colortex" + buffer + " must be positive");
            if (x < 16.0F) result[0] = x; else result[2] = x;
            if (y < 16.0F) result[1] = y; else result[3] = y;
            if (result[2] != 0 && result[3] == 0 || result[2] == 0 && result[3] != 0) throw new PackException("size.buffer.colortex" + buffer + " mixes a share of the screen with a number of pixels");
            return result;
        } catch (NumberFormatException e) {
            throw new PackException("size.buffer.colortex" + buffer + " must be numbers, not '" + value + "'");
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
            case "RGBA16F", "RGB16F" -> GpuFormat.RGBA16_FLOAT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "R8_SNORM" -> GpuFormat.R8_SNORM;
            case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
            case "RGBA8_SNORM", "RGB8_SNORM" -> GpuFormat.RGBA8_SNORM;
            case "R16_SNORM" -> GpuFormat.R16_SNORM;
            case "RG16_SNORM" -> GpuFormat.RG16_SNORM;
            case "RGBA16_SNORM", "RGB16_SNORM" -> GpuFormat.RGBA16_SNORM;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGBA32F", "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            default -> null;
        };
        if (format == null) throw new PackException(program + " gives colortex" + buffer + " the format " + name + ", which this version does not support");
        if (name.equals("RGB8") || name.equals("RGB16") || name.equals("RGB16F") || name.equals("RGB32F") || name.equals("RGB8_SNORM") || name.equals("RGB16_SNORM")) {
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
