package com.metallumextra;

import com.metallumextra.shader.IrisPipeline;
import com.metallumextra.shader.pack.TranslationCache;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * A running record of what the shaders cost, written to {@code <game folder>/metallum-extra/resources-<time>.log}: every step of loading a
 * shader pack and compiling its pipelines with the time it took, a snapshot of memory, GPU memory, threads and processor time every ten seconds
 * and at every pack change, and a table of totals when the game closes. Always on: it writes a few lines a second at most.
 */
public final class ResourceLog {
    private static final long START = System.nanoTime();
    private static final Map<String, Total> TOTALS = new ConcurrentHashMap<>();
    private static final long SLOW_NS = 40_000_000L;
    private static BufferedWriter out;
    private static long lastSnapshot;
    private static volatile boolean closed;

    private static final class Total {
        final LongAdder count = new LongAdder();
        final LongAdder nanos = new LongAdder();
        volatile long max;
    }

    static {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("metallum-extra");
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(dir.resolve("resources-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".log"));
            line("# Metallum Extra resource log. Times in ms since the game started; 'slow' lines are steps over 40 ms.");
            Runtime.getRuntime().addShutdownHook(new Thread(ResourceLog::close, "metallum-extra-resource-log"));
        } catch (IOException | RuntimeException e) {
            // Outside the game (the command-line checks) there is no game folder: nothing is written.
            out = null;
        }
    }

    private ResourceLog() {
    }

    /** Forces the class to load from the mod entrypoint, so the file exists from the start. */
    public static void touch() {
    }

    private static synchronized void line(final String text) {
        if (out == null || closed) return;
        try {
            out.write(String.format(java.util.Locale.ROOT, "%9.1f  %s%n", (System.nanoTime() - START) / 1.0e6, text));
            out.flush();
        } catch (IOException e) {
            out = null;
        }
    }

    public static void event(final String text) {
        line(text);
    }

    /** A step that took {@code ns}; it is added to the totals of its kind, and written out when it was slow. */
    public static void timed(final String kind, final String what, final long ns) {
        Total total = TOTALS.computeIfAbsent(kind, k -> new Total());
        total.count.increment();
        total.nanos.add(ns);
        if (ns > total.max) total.max = ns;
        if (ns >= SLOW_NS) line(String.format(java.util.Locale.ROOT, "slow  %-22s %8.1f ms  %s  [%s]", kind, ns / 1.0e6, what, Thread.currentThread().getName()));
    }

    /** Starts a step; {@code close()} on the result records it. */
    public static Step step(final String kind, final String what) {
        return new Step(kind, what, System.nanoTime());
    }

    public record Step(String kind, String what, long start) implements AutoCloseable {
        @Override
        public void close() {
            timed(kind, what, System.nanoTime() - start);
        }
    }

    /** Called every frame: a snapshot every ten seconds. */
    public static void tick() {
        long now = System.nanoTime();
        if (now - lastSnapshot > 10_000_000_000L) {
            lastSnapshot = now;
            snapshot("periodic");
        }
    }

    /** What the process holds right now. */
    public static void snapshot(final String why) {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        long heapMax = runtime.maxMemory() >> 20;
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        long nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() >> 20;
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        String os = "";
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
            os = String.format(java.util.Locale.ROOT, " cpu_time=%.1fs process_cpu_load=%.0f%% committed_virtual=%dMB", bean.getProcessCpuTime() / 1.0e9,
                    Math.max(0.0, bean.getProcessCpuLoad()) * 100.0, bean.getCommittedVirtualMemorySize() >> 20);
        }
        line(String.format(java.util.Locale.ROOT, "snapshot[%s] heap=%d/%dMB nonheap=%dMB gc=%dx/%dms threads=%d gpu_alloc=%dMB%s", why, heapUsed, heapMax, nonHeap, gcCount, gcMs, threads,
                FrameProfiler.gpuMemoryMb(), os));
        line("  shader buffers: " + IrisPipeline.summary());
        line("  translation cache: " + TranslationCache.summary());
    }

    private static synchronized void close() {
        if (closed) return;
        snapshot("exit");
        line("totals (count, total ms, mean ms, max ms):");
        for (Map.Entry<String, Total> entry : new TreeMap<>(TOTALS).entrySet()) {
            Total total = entry.getValue();
            long count = total.count.sum();
            double ms = total.nanos.sum() / 1.0e6;
            line(String.format(java.util.Locale.ROOT, "  %-24s %6d  %10.1f  %8.2f  %8.1f", entry.getKey(), count, ms, count == 0 ? 0 : ms / count, total.max / 1.0e6));
        }
        closed = true;
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
            // The game is closing.
        }
    }
}
