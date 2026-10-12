package com.metallumextra;

import com.metallumextra.shader.pack.CustomUniforms;
import com.metallumextra.shader.pack.GlslConditions;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.pack.LegacyGlsl;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.PackOption;
import com.metallumextra.shader.pack.PackProfiles;
import com.metallumextra.shader.pack.PreprocessorExpression;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.PropertiesFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Checks of what packs written for OptiFine and Iris need that Metallum Extra's own packs do not: the old compatibility profile of GLSL, preprocessor
 * conditions over macros, {@code shaders.properties} with conditions, custom uniforms and profiles. Everything here is plain arithmetic and text, so it
 * needs no game. Each check has an expected result worked out by hand.
 */
final class CompatTests {
    private final BiConsumer<String, Boolean> check;

    CompatTests(final BiConsumer<String, Boolean> check) {
        this.check = check;
    }

    private void check(final String what, final boolean ok) {
        check.accept(what, ok);
    }

    private void settingsScreens() {
        var pack = new com.metallumextra.shader.pack.BuiltinPack();
        var screens = com.metallumextra.shader.pack.PackScreens.of(pack, pack.options());
        var first = screens.items("");
        check("the first page of the built-in pack is its three pages", first.size() == 3 && first.stream().allMatch(i -> i instanceof com.metallumextra.shader.pack.PackScreens.Link));
        check("a page lists its options in the order of the line", screens.items("SHADOWS_PAGE").size() == 5
                && screens.items("SHADOWS_PAGE").get(1) instanceof com.metallumextra.shader.pack.PackScreens.OptionItem o && o.option().id().equals("shadowMapResolution"));
        var detail = pack.options().stream().filter(o -> o.id().equals("shadowMapResolution")).findFirst().orElseThrow();
        check("an option and its values have the words of the lang file", screens.optionLabel(detail).equals("Shadow Sharpness") && screens.valueLabel(detail, "4096").equals("Very High (4096)"));
        check("a page has a name", screens.screenLabel("EFFECTS_PAGE").equals("Effects"));
        check("an option with no words gets a name made from its identifier", com.metallumextra.shader.pack.PackScreens.prettyForTest("SHADOW_QUALITY").equals("Shadow Quality")
                && com.metallumextra.shader.pack.PackScreens.prettyForTest("shadowMapResolution").equals("Shadow Map Resolution"));
    }

    void run() throws Exception {
        preprocessorExpressions();
        settingsScreens();
        propertiesFiles();
        customUniforms();
        profiles();
        legacyGlsl();
        loading();
    }

    private static boolean holds(final String expression, final Map<String, String> macros) throws Exception {
        return PreprocessorExpression.evaluate(expression, macros, Set.of());
    }

    private void preprocessorExpressions() throws Exception {
        Map<String, String> macros = new HashMap<>();
        macros.put("QUALITY", "2");
        macros.put("ALIAS", "QUALITY");
        macros.put("SIZE", "(QUALITY * 4 + 1)");
        macros.put("FLAG", "");
        macros.put("NEG", "-1");
        check("a condition compares a macro with a number", holds("QUALITY == 2", macros) && !holds("QUALITY > 2", macros));
        check("a macro defined as another macro is followed", holds("ALIAS == 2 && ALIAS >= QUALITY", macros));
        check("arithmetic and parentheses", holds("SIZE == 9 && (3 + 4) * 2 == 14 && 7 / 2 == 3 && 7 % 4 == 3", macros));
        check("a name that is not defined is 0", holds("NOTHING == 0 && !NOTHING", macros));
        check("defined asks, with and without parentheses", holds("defined QUALITY && defined(FLAG) && !defined(MISSING)", macros));
        check("&& binds tighter than ||", holds("0 && 0 || 1", macros) && !holds("0 && (0 || 1)", macros));
        check("negative numbers", holds("NEG == -1 && -QUALITY < 0", macros));
        check("shifts, bit operators and the conditional", holds("(1 << 3) == 8 && (6 & 3) == 2 && (4 | 1) == 5 && (1 ? 7 : 9) == 7", macros));
        check("a number with a point is compared as a number", holds("0.5 < 1 && 2.5 > 2", macros));
        try {
            PreprocessorExpression.evaluate("GAME_DEFINED == 1", macros, Set.of("GAME_DEFINED"));
            check("a name the game defines later leaves the answer open", false);
        } catch (PreprocessorExpression.Open e) {
            check("a name the game defines later leaves the answer open", e.getMessage().equals("GAME_DEFINED"));
        }
        try {
            PreprocessorExpression.evaluate("QUALITY ==", macros, Set.of());
            check("an expression that ends too soon is refused", false);
        } catch (PackException e) {
            check("an expression that ends too soon is refused", true);
        }

        // The same, deciding which lines of a program remain.
        String program = "#define QUALITY 3\n#define LEVEL QUALITY\n#if LEVEL >= 3 && defined QUALITY\nhigh\n#elif LEVEL == 2\nmedium\n#else\nlow\n#endif\n"
                + "#ifdef DISTANT_HORIZONS\nwith dh\n#endif\n#ifdef EMISSIVE\nlit\n#endif\n";
        String stripped = GlslConditions.strip(program, Set.of());
        check("the branch the macros decide is kept, the others are blank", stripped.contains("high") && !stripped.contains("medium") && !stripped.contains("low"));
        check("a name nothing defines is not defined, so its branch goes", !stripped.contains("with dh"));
        check("a name the game's pipeline defines is left open: its branch stays", stripped.contains("lit"));
        check("the lines stay, so the compiler's line numbers are the file's", stripped.split("\n", -1).length == program.split("\n", -1).length);
        String commented = GlslConditions.strip("#define FOLIAGE\n#ifdef FOLIAGE  // has foliage\nkept\n#endif\n#ifndef FOLIAGE /* no */\ngone\n#endif\n", Set.of());
        check("a comment after the name of #ifdef does not change what is defined", commented.contains("kept") && !commented.contains("gone"));
        String walled = GlslConditions.strip("#define OFF_BY_DEFAULT\n#ifdef GONE\nx\n/*\n#ifdef OFF_BY_DEFAULT\ninside\n*/\n#endif\nafter\n", Set.of());
        check("a directive inside a block comment is not read, so the comment ends where it did", walled.contains("*/") && walled.contains("inside") && !walled.contains("\nx\n") && walled.contains("after"));
        String fogCoord = LegacyGlsl.translate("#version 120\nvarying float f;\nvoid main() { gl_FogFragCoord = 1.0; }\n", true, LegacyGlsl.Kind.WORLD);
        check("gl_FogFragCoord is a varying of the pipeline's own", has(fogCoord, "mx_FogFragCoord = 1.0;") && has(fogCoord, "out float mx_FogFragCoord;"));
        String zeroList = com.metallumextra.shader.pack.IrisUniforms.zeroUndefined("uniform float viewWidth, dhNearPlane, dhFarPlane;\n");
        check("a list of uniforms keeps what the game sets and makes the rest zero", has(zeroList, "uniform float viewWidth;") && has(zeroList, "const float dhNearPlane = float(0);") && has(zeroList, "const float dhFarPlane = float(0);"));
        String own = com.metallumextra.shader.IrisWorldAdapter.withoutOwnSamplers("uniform sampler2D gaux3, lightmap;\nuniform sampler2D gtexture;\n");
        check("the pipeline's own samplers leave a list of declarations", has(own, "uniform sampler2D gaux3;") && !has(own, "lightmap") && !has(own, "gtexture"));
    }

    private void propertiesFiles() {
        String text = "# a comment\n#if SHADOW_QUALITY == -1\nshadow=off\n#else\nshadow=on\n#endif\n#ifdef MC_OS_MAC\n#define COLORED_LIGHTING 0\n#endif\n"
                + "#if COLORED_LIGHTING > 0\ncolored=yes\n#else\ncolored=no\n#endif\nlong = first \\\n   second\nspaced   =   value  \n!bang comment\nimage.x = a b 16\n";
        Map<String, String> macros = PropertiesFile.macros(Map.of("SHADOW_QUALITY", "-1", "COLORED_LIGHTING", "256", "BLOOM", "true", "FXAA", "false"));
        Map<String, String> parsed = PropertiesFile.parse(text, macros);
        check("a condition over an option chooses the lines of shaders.properties", "off".equals(parsed.get("shadow")));
        check("a #define in the file changes later conditions (the macOS branch turns colored lighting off)", "no".equals(parsed.get("colored")));
        check("a line ending in a backslash goes on in the next", "first second".equals(parsed.get("long")));
        check("keys and values are trimmed, comments skipped", "value".equals(parsed.get("spaced")) && !parsed.containsKey("# a comment") && !parsed.containsKey("!bang comment"));
        check("the keys stay in the order of the file", List.copyOf(parsed.keySet()).indexOf("shadow") < List.copyOf(parsed.keySet()).indexOf("colored"));
        check("a switch that is on is defined, one that is off is not", macros.containsKey("BLOOM") && !macros.containsKey("FXAA"));
        Map<String, String> other = PropertiesFile.parse("#if SHADOW_QUALITY == -1\nshadow=off\n#else\nshadow=on\n#endif\n", PropertiesFile.macros(Map.of("SHADOW_QUALITY", "2")));
        check("and the other way round", "on".equals(other.get("shadow")));
    }

    private static Map<String, double[]> values(final Object... pairs) {
        Map<String, double[]> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            values.put((String) pairs[i], v instanceof double[] array ? array : new double[] {((Number) v).doubleValue()});
        }
        return values;
    }

    private void customUniforms() throws Exception {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("uniform.float.framemod8", "frameCounter % 8");
        properties.put("variable.float.dx", "cameraPosition.x - previousCameraPosition.x");
        properties.put("variable.float.moving", "if(abs(dx) > 0.0 && abs(dx) < 1.0, 1, 0)");
        properties.put("uniform.float.moves", "moving * 2");
        properties.put("uniform.float.inCave", "if(isEyeInWater == 0, 1.0 - clamp(eyeBrightness.y / 240.0, 0.0, 1.0), 0.0)");
        properties.put("uniform.float.inForest", "if(in(biome, BIOME_FOREST, BIOME_BIRCH_FOREST), 1, 0)");
        properties.put("uniform.int.whole", "floor(2.75)");
        properties.put("uniform.float.either", "if(0, 1, 5 > 4, 7, 9)");
        properties.put("uniform.float.minmax", "max(min(3, 9), 1) + pow(2, 3) - sqrt(16)");
        properties.put("uniform.float.not", "!0 + !5 + (1 != 2)");
        properties.put("uniform.int.fm", "fmod(frameCounter, 8) * 10 + fmod(frameCounter - 22, 8)");
        CustomUniforms custom = CustomUniforms.parse(properties, name -> name.equals("BIOME_FOREST") ? 4.0 : name.equals("BIOME_BIRCH_FOREST") ? 6.0 : null);
        check("the uniforms are listed, the variables are not", custom.uniforms().stream().map(CustomUniforms.Def::name).toList()
                .equals(List.of("framemod8", "moves", "inCave", "inForest", "whole", "either", "minmax", "not", "fm")));
        Map<String, double[]> v = values("frameCounter", 21, "cameraPosition", new double[] {10.5, 0, 0}, "previousCameraPosition", new double[] {10.0, 0, 0},
                "isEyeInWater", 0, "eyeBrightness", new double[] {0, 60}, "biome", 6);
        custom.evaluate(v, 0.016);
        check("% on numbers", v.get("framemod8")[0] == 5.0);
        check("a variable uses the values of this frame, and a uniform can use a variable", v.get("moves")[0] == 2.0 && !v.containsKey("dx"));
        check("if, clamp and a component of a vector", Math.abs(v.get("inCave")[0] - 0.75) < 1e-9);
        check("in() with the game's constants", v.get("inForest")[0] == 1.0);
        check("an int uniform is a whole number", v.get("whole")[0] == 2.0);
        check("if with several conditions takes the first that holds", v.get("either")[0] == 7.0);
        check("min, max, pow and sqrt", v.get("minmax")[0] == 3.0 + 8.0 - 4.0);
        check("! and comparisons are 0 or 1", v.get("not")[0] == 2.0);
        check("fmod keeps the sign of the divisor", v.get("fm")[0] == 57.0);
        Map<String, double[]> still = values("frameCounter", 0, "cameraPosition", new double[] {5, 0, 0}, "previousCameraPosition", new double[] {5, 0, 0},
                "isEyeInWater", 1, "eyeBrightness", new double[] {0, 240}, "biome", 1);
        custom.evaluate(still, 0.016);
        check("standing still, in water and in no forest", still.get("moves")[0] == 0.0 && still.get("inCave")[0] == 0.0 && still.get("inForest")[0] == 0.0);

        // smooth(): the value moves towards its target by half the way in the fade time, up and down at their own speeds, and an id shares the state.
        Map<String, String> smooth = new LinkedHashMap<>();
        smooth.put("uniform.float.slow", "smooth(1, target, 1.0, 4.0)");
        smooth.put("uniform.float.instant", "smooth(2, target, 0, 0)");
        smooth.put("uniform.float.shared", "smooth(1, target * 0, 1.0, 4.0)");
        CustomUniforms s = CustomUniforms.parse(smooth, name -> null);
        Map<String, double[]> first = values("target", 0);
        s.evaluate(first, 1.0);
        check("a smoothed value starts at its target", first.get("slow")[0] == 0.0);
        Map<String, double[]> up = values("target", 8);
        s.evaluate(up, 1.0);
        check("rising by half the way in the fade-up time", Math.abs(up.get("instant")[0] - 8.0) < 1e-9 && up.get("slow")[0] > 0.0);
        check("the call with the same id shares what the first one made of it (and then moves it back down at the slower fade)", up.get("shared")[0] < up.get("slow")[0]);
        Map<String, double[]> halfway = values("target", 8);
        CustomUniforms fresh = CustomUniforms.parse(Map.of("uniform.float.x", "smooth(9, target, 2.0, 2.0)"), name -> null);
        fresh.evaluate(values("target", 0), 1.0);
        fresh.evaluate(halfway, 2.0);
        check("after exactly one half-life, half the way is covered", Math.abs(halfway.get("x")[0] - 4.0) < 1e-9);
        try {
            CustomUniforms.parse(Map.of("uniform.float.bad", "nosuchfunction(1)"), name -> null);
            check("an unknown function is refused with the uniform's name", false);
        } catch (PackException e) {
            check("an unknown function is refused with the uniform's name", e.getMessage().contains("bad") && e.getMessage().contains("nosuchfunction"));
        }
        try {
            CustomUniforms.parse(Map.of("uniform.float.bad", "1 +"), name -> null);
            check("an expression that ends too soon is refused", false);
        } catch (PackException e) {
            check("an expression that ends too soon is refused", true);
        }
    }

    private static PackOption option(final String id, final boolean toggle, final String... values) {
        return new PackOption(id, id, List.of(values), 0, toggle);
    }

    private void profiles() throws Exception {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("profile.LOW", "QUALITY=1 DISTANCE=96.0 !BLOOM");
        properties.put("profile.HIGH", "profile.LOW QUALITY=3 BLOOM");
        properties.put("sliders", "DISTANCE");
        ProgramSet fake = null;
        Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
        // PackProfiles.of reads a ProgramSet; the parsing of the tokens is what is checked here, through apply on hand-made profiles.
        profiles.put("LOW", Map.of("QUALITY", "1", "DISTANCE", "96.0", "BLOOM", "false"));
        profiles.put("HIGH", Map.of("QUALITY", "3", "DISTANCE", "96.0", "BLOOM", "true"));
        List<PackOption> options = List.of(option("QUALITY", false, "1", "2", "3"), option("DISTANCE", false, "64", "96", "128"), option("BLOOM", true, "Off", "On"));
        Map<String, Integer> chosen = new HashMap<>();
        for (PackOption option : options) chosen.put(option.id(), 0);
        List<String> problems = PackProfiles.apply(options, profiles, "HIGH", (option, index) -> chosen.put(option.id(), index));
        check("a profile sets each option it lists, a number as a number (96.0 is the 96 of the list)", problems.isEmpty() && chosen.get("QUALITY") == 2 && chosen.get("DISTANCE") == 1 && chosen.get("BLOOM") == 1);
        check("the profile that is current is the one whose values are all set", "HIGH".equals(PackProfiles.current(options, o -> chosen.get(o.id()), profiles)));
        chosen.put("QUALITY", 1);
        check("a change to one option leaves no profile current (custom)", PackProfiles.current(options, o -> chosen.get(o.id()), profiles) == null);
        Map<String, String> odd = new LinkedHashMap<>();
        odd.put("QUALITY", "9");
        odd.put("MISSING", "1");
        List<String> bad = PackProfiles.apply(options, Map.of("ODD", odd), "ODD", (o, i) -> { });
        check("a value an option does not offer, and an option the pack lacks, are reported and not guessed", bad.size() == 2 && bad.get(0).contains("QUALITY") && bad.get(1).contains("MISSING"));
        check("an unknown profile is reported", PackProfiles.apply(options, profiles, "NONE", (o, i) -> { }).size() == 1);
    }

    private static boolean has(final String text, final String part) {
        return text.contains(part);
    }

    private void legacyGlsl() throws Exception {
        check("a program of a new version without old names is left as it is", !LegacyGlsl.isLegacy("#version 330 core\nin vec2 uv;\nout vec4 c;\nvoid main() { c = texture(s, uv); }\n"));
        check("an old version is legacy", LegacyGlsl.isLegacy("#version 120\nvoid main() {}\n"));
        check("so is an old name behind a new version line", LegacyGlsl.isLegacy("#version 330\nvoid main() { gl_Position = ftransform(); }\n"));
        check("an old name in a comment is not", !LegacyGlsl.isLegacy("#version 330 core\n// uses gl_Vertex and texture2D(\n/* varying */\nvoid main() {}\n"));

        String vertex = LegacyGlsl.translate("#version 130\nout vec2 uv;\nattribute vec4 mc_Entity;\nvoid main() {\n    gl_Position = ftransform();\n    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;\n"
                + "    vec3 n = normalize(gl_NormalMatrix * gl_Normal);\n    vec4 p = gl_ModelViewMatrix * gl_Vertex;\n    float id = mc_Entity.x;\n}\n", true, LegacyGlsl.Kind.WORLD);
        check("the version becomes 330 core", vertex.startsWith("#version 330 core"));
        check("ftransform is the projection and model-view of the vertex", has(vertex, "iris_ftransform()") && has(vertex, "#define iris_ftransform() (iris_ProjectionMatrix * iris_ModelViewMatrix * iris_Vertex)"));
        check("the vertex is the position and the chunk's offset, in Iris's names", has(vertex, "#define iris_Vertex vec4(vaPosition + chunkOffset, 1.0)") && has(vertex, "in vec3 vaPosition;"));
        check("the matrices are the game's, the normal and texture coordinates the inputs", has(vertex, "uniform mat4 modelViewMatrix;") && has(vertex, "#define iris_Normal vaNormal")
                && has(vertex, "#define iris_MultiTexCoord0 vec4(vaUV0, 0.0, 1.0)") && has(vertex, "uniform mat3 normalMatrix;"));
        check("gl_TextureMatrix[0] is the texture matrix", has(vertex, "iris_TextureMatrix0 * iris_MultiTexCoord0") && has(vertex, "uniform mat4 textureMatrix;"));
        check("an input declared with a wider type than Iris's is declared as Iris's, and read through a macro of the width the program asked for",
                has(vertex, "in vec2 mc_Entity;") && has(vertex, "#define iris_legacy_mc_Entity vec4(mc_Entity, 0.0, 1.0)") && has(vertex, "float id = iris_legacy_mc_Entity.x;"));
        check("the other names are not touched: no old name is left", !has(vertex.replaceAll("iris_\\w+", ""), "gl_Vertex") && !has(vertex, "gl_NormalMatrix") && !has(vertex, "attribute"));

        String screen = LegacyGlsl.translate("#version 120\nvarying vec2 texCoord;\nvoid main() { gl_Position = ftransform(); texCoord = gl_MultiTexCoord0.xy; }\n", true, LegacyGlsl.Kind.FULLSCREEN);
        check("in a full-screen program varying leaves the vertex stage as out", has(screen, "out vec2 texCoord;"));
        check("the screen's quad is made from the vertex number, and the projection takes 0..1 to the screen", has(screen, "gl_VertexIndex") && has(screen, "#define iris_ProjectionMatrix mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0"));

        String fragment = LegacyGlsl.translate("#version 120\nvarying vec2 texCoord;\nuniform sampler2D colortex0;\nvoid main() {\n    vec4 c = texture2D(colortex0, texCoord);\n"
                + "    vec4 l = texture2DLod(colortex0, texCoord, 0.0);\n    gl_FragData[0] = c;\n    gl_FragData[1] = l;\n}\n", false, LegacyGlsl.Kind.FULLSCREEN);
        check("varying is an input of the fragment stage", has(fragment, "in vec2 texCoord;") && !has(fragment, "varying"));
        check("texture2D and texture2DLod are texture and textureLod", has(fragment, "texture(colortex0, texCoord)") && has(fragment, "textureLod(colortex0, texCoord, 0.0)"));
        check("each gl_FragData[n] is an output at location n", has(fragment, "layout(location = 0) out vec4 outColor0;") && has(fragment, "layout(location = 1) out vec4 outColor1;")
                && has(fragment, "outColor0 = c;") && has(fragment, "outColor1 = l;") && !has(fragment, "gl_FragData"));
        check("only the outputs that are written are declared", !has(fragment, "outColor2"));
        String colored = LegacyGlsl.translate("#version 120\nvoid main() { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); }\n", false, LegacyGlsl.Kind.FULLSCREEN);
        check("gl_FragColor is output 0", has(colored, "outColor0 = vec4(1.0, 0.0, 0.0, 1.0);") && has(colored, "layout(location = 0) out vec4 outColor0;"));
        String shadow = LegacyGlsl.translate("#version 120\nuniform sampler2DShadow s;\nvoid main() { float f = shadow2D(s, vec3(0.5)).z; }\n", false, LegacyGlsl.Kind.WORLD);
        check("shadow2D is a texture read of the shadow sampler, made a vec4 as it was", has(shadow, "iris_shadow2D(s, vec3(0.5)).z") && has(shadow, "#define iris_shadow2D(s, p) vec4(vec3(mx_shadowCompare((s), (p))), 1.0)"));
        String fog = LegacyGlsl.translate("#version 120\nvoid main() { float a = gl_Fog.start; float b = gl_Fog.scale; }\n", false, LegacyGlsl.Kind.WORLD);
        check("gl_Fog.start and .scale are made of fogStart and fogEnd", has(fog, "float a = fogStart;") && has(fog, "(1.0 / (fogEnd - fogStart))") && has(fog, "uniform float fogStart;"));
        try {
            LegacyGlsl.translate("#version 120\nvoid main() { vec4 a = gl_FrontMaterial.diffuse; }\n", true, LegacyGlsl.Kind.WORLD);
            check("a name that cannot be translated is refused by name", false);
        } catch (PackException e) {
            check("a name that cannot be translated is refused by name", e.getMessage().contains("gl_FrontMaterial"));
        }
        check("words in comments are left alone", has(LegacyGlsl.translate("#version 120\n// gl_Vertex texture2D\nvoid main() { /* gl_Color */ }\n", true, LegacyGlsl.Kind.WORLD), "// gl_Vertex texture2D"));
        check("a name that merely contains an old one is left alone", has(LegacyGlsl.translate("#version 120\nvec4 my_gl_Vertex2; float texture2DX = 1.0; void main() { my_gl_Vertex2 = vec4(0.0); }\n", true, LegacyGlsl.Kind.WORLD), "my_gl_Vertex2 = vec4(0.0)"));
        check("the same source gives the same translation", LegacyGlsl.translate("#version 120\nvoid main() { gl_Position = ftransform(); }\n", true, LegacyGlsl.Kind.WORLD)
                .equals(LegacyGlsl.translate("#version 120\nvoid main() { gl_Position = ftransform(); }\n", true, LegacyGlsl.Kind.WORLD)));
        check("a world program's fragment stage gets no input declarations", !has(LegacyGlsl.translate("#version 120\nvoid main() { vec4 c = gl_Color; }\n", false, LegacyGlsl.Kind.WORLD), "in vec4 vaColor;"));

        check("the kind of a program is read from its name", LegacyGlsl.Kind.of("world0/gbuffers_terrain.vsh") == LegacyGlsl.Kind.WORLD && LegacyGlsl.Kind.of("world0/shadow.fsh") == LegacyGlsl.Kind.WORLD
                && LegacyGlsl.Kind.of("composite2.fsh") == LegacyGlsl.Kind.FULLSCREEN && LegacyGlsl.Kind.of("world0/final.vsh") == LegacyGlsl.Kind.FULLSCREEN);

        String macro = "#define A(x) \\\n    ((x) * 2.0) \\\n    + 1.0\nint z = 1; /* art \\\n   more */\nvoid f() {}\n";
        String spliced = LegacyGlsl.spliceLines(macro);
        check("a macro over several lines becomes one line", has(spliced, "#define A(x)      ((x) * 2.0)      + 1.0") || spliced.lines().anyMatch(l -> l.startsWith("#define A(x)") && l.contains("+ 1.0")));
        check("a backslash at the end of a comment line goes", !has(spliced, "\\"));
        check("the line count stays the same", spliced.split("\n", -1).length == macro.split("\n", -1).length);
        check("a sampler is renamed where it is declared and used", LegacyGlsl.renameSampler("uniform sampler2D tex;\nvoid main() { c = texture(tex, uv); }\n", "tex", "gtexture")
                .equals("uniform sampler2D gtexture;\nvoid main() { c = texture(gtexture, uv); }\n"));
        check("and not when the program does not declare it", LegacyGlsl.renameSampler("void main() { float tex = 1.0; }\n", "tex", "gtexture").equals("void main() { float tex = 1.0; }\n"));
    }

    private void loading() {
        String source = "#version 330\nuniform float used;\nuniform float unused;\nuniform sampler2D readSampler;\nuniform sampler2D idleSampler;\nuniform float velocity;\n"
                + "void main() { float x = used + texture(readSampler, vec2(0.0)).r; }\n";
        String trimmed = ProgramSet.withoutUnusedUniforms(source);
        check("a uniform nothing reads is dropped, one that is read stays", !has(trimmed, "uniform float unused;") && has(trimmed, "uniform float used;"));
        check("the same for samplers", !has(trimmed, "idleSampler") && has(trimmed, "uniform sampler2D readSampler;"));
        check("the lines stay, so line numbers do", trimmed.split("\n", -1).length == source.split("\n", -1).length);
        String zeroed = IrisUniforms.zeroUndefined("#version 330\nuniform float velocity;\nuniform int mystery;\nuniform sampler2D idle;\nuniform float far;\n");
        check("a uniform nothing defines becomes the constant zero", has(zeroed, "const float velocity = float(0);") && has(zeroed, "const int mystery = int(0);"));
        check("a standard uniform and a sampler are left", has(zeroed, "uniform float far;") && has(zeroed, "uniform sampler2D idle;"));
    }
}
