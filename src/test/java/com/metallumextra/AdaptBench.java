package com.metallumextra;

import com.metallumextra.shader.IrisTerrain;
import com.metallumextra.shader.pack.IrisPlan;
import com.metallumextra.shader.pack.IrisUniforms;
import com.metallumextra.shader.pack.ZipPack;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Times and samples what the game does to one program of a pack before compiling it: {@code ./gradlew adaptBench -Pzip=<file> -Pprogram=world0/gbuffers_terrain.vsh}. */
public final class AdaptBench {
    private AdaptBench() {
    }

    public static void main(final String[] args) throws Exception {
        ZipPack pack = ZipPack.open(Path.of(args[0]));
        IrisPlan.build(pack, "world0");
        String text = pack.load(args[1]);
        for (int i = 0; i < 4; i++) {
            long t = System.nanoTime();
            pack.loadUncached(args[1]);
            System.out.printf("load %d: %d ms%n", i, (System.nanoTime() - t) / 1_000_000);
        }
        System.out.println(args[1] + ": " + text.length() + " characters, " + text.split("\n").length + " lines");
        Map<String, Integer> samples = new HashMap<>();
        Thread main = Thread.currentThread();
        Thread sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
                StackTraceElement[] stack = main.getStackTrace();
                for (StackTraceElement frame : stack) {
                    if (frame.getClassName().startsWith("com.metallumextra") && !frame.getClassName().endsWith("AdaptBench") && !frame.getClassName().contains("IrisPipeline")) {
                        samples.merge(frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber(), 1, Integer::sum);
                        break;
                    }
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            IrisPlan.build(pack, "world0");
            System.out.printf("plan %d: %d ms%n", i, (System.nanoTime() - t) / 1_000_000);
        }
        for (int i = 0; i < 4; i++) {
            long t0 = System.nanoTime();
            String clean = IrisPlan.withoutBufferFormats(text);
            long t1 = System.nanoTime();
            String adapted = IrisTerrain.adapt(clean, args[1].endsWith(".vsh"), false, false, true);
            long t2 = System.nanoTime();
            var declared = IrisUniforms.declared(adapted);
            long t3 = System.nanoTime();
            String rewritten = IrisUniforms.rewrite(adapted, declared);
            long t4 = System.nanoTime();
            System.out.printf("run %d: buffer formats %d ms, terrain adapt %d ms, declared %d ms, rewrite %d ms (%d characters out)%n", i, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000, rewritten.length());
        }
        samples.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12).forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
    }
}
