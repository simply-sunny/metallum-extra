package com.metallumextra;

import com.metallumextra.shader.ComputeCompiler;
import com.metallumextra.shader.GeometryKernel;
import com.metallumextra.shader.IrisWorldAdapter;
import com.metallumextra.shader.pack.ShaderPack;
import com.metallumextra.shader.pack.ZipPack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the compute kernel of each geometry stage in a pack's sky and terrain programs, and compiles it the way a compute program is compiled. Run
 * with {@code gradlew -I local-testing.init.gradle geometryCheck -Pzip=<pack> [-Pdump=<folder>]}. Nothing is drawn.
 */
public final class GeometryKernelCheck {
    /** The sun and the moon: POSITION_TEX, which the game draws as quads (see RenderPipelines#CELESTIAL). */
    private static final GeometryKernel.VertexLayout CELESTIAL = new GeometryKernel.VertexLayout(20, List.of(
            new GeometryKernel.Attribute("Position", 0, 3, false), new GeometryKernel.Attribute("UV0", 12, 2, false)));
    private static final IrisWorldAdapter.Format CELESTIAL_FORMAT = new IrisWorldAdapter.Format(false, true, false, false, false, false, true, false, false, false);

    private GeometryKernelCheck() {
    }

    public static void main(final String[] args) throws Exception {
        ShaderPack pack = ZipPack.open(Path.of(args[0]));
        Path dump = args.length > 1 ? Path.of(args[1]) : null;
        if (dump != null) Files.createDirectories(dump);
        boolean failed = false;
        String[][] stages = {{"gbuffers_skytextured", "sky"}};
        for (String[] stage : stages) {
            String name = stage[0];
            if (pack.read(name + ".gsh") == null) {
                System.out.println("skip " + name + ": no geometry stage");
                continue;
            }
            try {
                String geometry = GeometryKernel.body(pack, name + ".gsh", name + ".vsh");
                String vertex = IrisWorldAdapter.adapt(pack.load(name + ".vsh"), true, CELESTIAL_FORMAT);
                GeometryKernel.Built built = GeometryKernel.build(vertex, geometry, CELESTIAL);
                if (dump != null) Files.writeString(dump.resolve(name + ".kernel.glsl"), built.glsl());
                ComputeCompiler.Compiled compiled = ComputeCompiler.compile(name + " geometry", built.glsl());
                System.out.println("ok    " + name + ": " + built.maxVertices() + " vertices per triangle, " + built.outputFloats() + " floats each, "
                        + compiled.metal().length() + " bytes of Metal, resources " + compiled.resources());
            } catch (RuntimeException e) {
                failed = true;
                System.out.println("FAIL  " + name + ": " + e.getMessage());
            }
        }
        if (failed) System.exit(1);
    }
}
