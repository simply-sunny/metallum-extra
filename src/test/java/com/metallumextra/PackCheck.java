package com.metallumextra;

import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.PackException;
import com.metallumextra.shader.pack.ProgramSet;
import com.metallumextra.shader.pack.ZipPack;

import java.nio.file.Path;

/**
 * Opens a shader pack ZIP and plans every dimension the way the game would, reporting what blocks it. No game needed:
 * {@code ./gradlew packCheck -Pzip=<file>}. The ZIP is only read.
 */
public final class PackCheck {
    private PackCheck() {
    }

    public static void main(String[] args) throws Exception {
        ZipPack pack = ZipPack.open(Path.of(args[0]));
        if (args.length > 2 && args[1].equals("profile")) {
            var profiles = com.metallumextra.shader.pack.PackProfiles.of(ProgramSet.discover(pack, "world0"));
            System.out.println("profiles " + profiles.keySet());
            for (String problem : com.metallumextra.shader.pack.PackProfiles.apply(pack.options(), profiles, args[2], (option, index) -> com.metallumextra.shader.pack.PackOptions.set(pack.name(), option, index))) System.out.println("  PROBLEM: " + problem);
            System.out.println("current profile: " + com.metallumextra.shader.pack.PackProfiles.current(pack.options(), option -> com.metallumextra.shader.pack.PackOptions.get(pack.name(), option), profiles));
            String[] rest = new String[args.length - 2];
            rest[0] = args[0];
            System.arraycopy(args, 3, rest, 1, args.length - 3);
            args = rest;
        }
        if (args.length > 2 && args[1].equals("dump")) {
            // The program as the loader reads it (includes, options, macros, translation), optionally with the conditions decided.
            String text = pack.load(args[2]);
            System.out.println(args.length > 3 ? com.metallumextra.shader.pack.GlslConditions.strip(text, java.util.Set.of()) : text);
            return;
        }
        long t0 = System.nanoTime();
        try {
            pack.load("world0/composite.fsh");
        } catch (IllegalStateException e) {
            System.out.println("(world0/composite.fsh is not in this pack)");
        }
        long t1 = System.nanoTime();
        System.out.println("one program loads in " + (t1 - t0) / 1_000_000 + " ms");
        System.out.println("pack " + pack.name() + ", " + pack.files().size() + " files, " + pack.options().size() + " options");
        for (String dimension : ProgramSet.DIMENSIONS) {
            ProgramSet set = ProgramSet.discover(pack, dimension);
            System.out.println(dimension + ": runnable " + set.runnable().size() + ", not run yet " + set.unsupported());
            try {
                IrisPlan plan = IrisPlan.build(pack, dimension);
                System.out.println(dimension + ": PLAN OK, " + plan.stepCount() + " steps, buffers " + plan.buffers().keySet());
                for (String note : plan.notes()) System.out.println("  note: " + note);
            } catch (PackException e) {
                System.out.println(dimension + ": BLOCKED: " + e.getMessage());
            }
        }
    }
}
