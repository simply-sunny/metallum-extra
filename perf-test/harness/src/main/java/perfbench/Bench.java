package perfbench;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Scripted frame measurements, the same for every renderer it runs under. The script (-Dperfbench.script=file) is one step per line:
 * <pre>
 * world NAME             opens that world (it must exist) from the title screen; always the first line
 * wait MS                lets time pass (frames keep being drawn)
 * cmd COMMAND            a command, run by the integrated server
 * opt NAME VALUE         renderDistance, simulationDistance, vsync (on/off), fps (limit; 260 is unlimited), fov
 * res WxH                window size
 * spin DEG_PER_SECOND    turns the player at that rate while frames are drawn (0 stops)
 * bench NAME SECONDS     records every frame for that long: frames-NAME.csv, and a line of statistics in summary.txt
 * mx LINE                a line of Metallum Extra's debug script (when that mod is present)
 * iris on PACK | off     Iris: the shader pack to use, or none
 * shot NAME             saves the world image as NAME.png in the output folder
 * log TEXT               a line in the log and in summary.txt
 * quit                   closes the game
 * </pre>
 * Beside the frames, the JVM's own numbers (heap, collections, threads, CPU) are written once a second to samples.csv.
 */
public final class Bench {
    private static final Path SCRIPT = path("perfbench.script");
    private static final Path OUT = path("perfbench.out");

    private static ArrayDeque<String> steps;
    private static boolean worldOpened;
    private static long resumeAt;
    private static boolean quitting;

    // the frame in progress
    private static long frameStart, lastFrameStart, frameCpuStart;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    // a recording in progress
    private static String benchName;
    private static long benchEnd, benchStart;
    private static long[] interval = new long[1 << 16], body = new long[1 << 16], cpu = new long[1 << 16];
    private static int frames;
    private static long gcCount0, gcTime0;
    private static long procCpu0;
    private static double heapSum;
    private static int heapN;

    private static double spin;
    private static double spinBase;
    private static long spinStart;

    private static String phase = "start";
    private static long lastSample;
    private static final List<GarbageCollectorMXBean> GCS = ManagementFactory.getGarbageCollectorMXBeans();

    private Bench() {
    }

    private static Path path(final String property) {
        String value = System.getProperty(property);
        return value == null ? null : Path.of(value);
    }

    public static void frameBegin(final Minecraft minecraft) {
        if (SCRIPT == null) return;
        frameStart = System.nanoTime();
        frameCpuStart = THREADS.getCurrentThreadCpuTime();
        if (benchName != null && lastFrameStart != 0) record(frameStart - lastFrameStart);
        lastFrameStart = frameStart;
        sample(minecraft);
        if (spin != 0 && minecraft.player != null) {
            float yaw = (float) (spinBase + spin * (frameStart - spinStart) / 1.0e9);
            minecraft.player.setYRot(yaw);
            minecraft.player.setYHeadRot(yaw);
        }
        tick(minecraft);
    }

    public static void frameEnd() {
        if (SCRIPT == null || benchName == null || frames == 0) return;
        body[frames - 1] = System.nanoTime() - frameStart;
        cpu[frames - 1] = THREADS.getCurrentThreadCpuTime() - frameCpuStart;
    }

    private static void record(final long intervalNs) {
        // The interval is that of the frame before; its body and cpu are stored when it ends, so this frame takes the next slot.
        if (frames == interval.length) {
            interval = Arrays.copyOf(interval, frames * 2);
            body = Arrays.copyOf(body, frames * 2);
            cpu = Arrays.copyOf(cpu, frames * 2);
        }
        interval[frames] = intervalNs;
        frames++;
        if (frameStart >= benchEnd) finishBench();
    }

    // ---- once a second: the JVM's numbers ----

    private static void sample(final Minecraft minecraft) {
        long now = System.nanoTime();
        if (now - lastSample < 1_000_000_000L) return;
        lastSample = now;
        Runtime rt = Runtime.getRuntime();
        double heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        var memory = ManagementFactory.getMemoryMXBean();
        double nonHeap = memory.getNonHeapMemoryUsage().getUsed() / 1048576.0;
        double direct = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) direct += pool.getMemoryUsed() / 1048576.0;
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : GCS) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcTime += Math.max(0, gc.getCollectionTime());
        }
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        heapSum += heapUsed;
        heapN++;
        append("samples.csv", "wall_ms,phase,heap_used_mb,heap_committed_mb,non_heap_mb,direct_mb,gc_count,gc_time_ms,threads,process_cpu_load,system_cpu_load,process_cpu_s,fps\n",
                String.format(Locale.ROOT, "%d,%s,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%.3f,%.3f,%.2f,%d%n", System.currentTimeMillis(), phase, heapUsed, rt.totalMemory() / 1048576.0, nonHeap, direct,
                        gcCount, gcTime, THREADS.getThreadCount(), os.getProcessCpuLoad(), os.getCpuLoad(), os.getProcessCpuTime() / 1.0e9, minecraft.getFps()));
    }

    private static void append(final String file, final String header, final String line) {
        if (OUT == null) return;
        try {
            Files.createDirectories(OUT);
            Path target = OUT.resolve(file);
            if (!Files.exists(target)) Files.writeString(target, header, StandardOpenOption.CREATE);
            Files.writeString(target, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[perfbench] could not write " + file + ": " + e);
        }
    }

    // ---- the script ----

    private static void tick(final Minecraft minecraft) {
        if (quitting) return;
        if (minecraft.level == null || minecraft.player == null) {
            openWorld(minecraft);
            return;
        }
        if (steps == null) {
            if (minecraft.gui.screen() != null) return;
            try {
                steps = new ArrayDeque<>(Files.readAllLines(SCRIPT));
                // The first line only names the world.
                resumeAt = System.nanoTime() + 1_500_000_000L;
            } catch (IOException e) {
                System.err.println("[perfbench] could not read the script: " + e);
                steps = new ArrayDeque<>();
            }
        }
        while (System.nanoTime() >= resumeAt && !steps.isEmpty() && !quitting) run(minecraft, steps.poll().trim());
    }

    private static void openWorld(final Minecraft minecraft) {
        if (worldOpened || !minecraft.isGameLoadFinished() || minecraft.gui.overlay() != null || !(minecraft.gui.screen() instanceof TitleScreen)) return;
        worldOpened = true;
        try {
            for (String line : Files.readAllLines(SCRIPT)) {
                if (!line.trim().startsWith("world ")) continue;
                String name = line.trim().substring(6).trim();
                log("opening world " + name);
                WorldOpenFlows flows = minecraft.createWorldOpenFlows();
                flows.openWorld(name, () -> log("could not open world " + name));
                return;
            }
        } catch (IOException e) {
            System.err.println("[perfbench] could not read the script: " + e);
        }
    }

    private static void run(final Minecraft minecraft, final String step) {
        if (step.isEmpty() || step.startsWith("#") || step.startsWith("world ")) return;
        int space = step.indexOf(' ');
        String verb = space < 0 ? step : step.substring(0, space);
        String rest = space < 0 ? "" : step.substring(space + 1).trim();
        log("step: " + step);
        long began = System.nanoTime();
        try {
            switch (verb) {
                case "wait" -> resumeAt = System.nanoTime() + Long.parseLong(rest) * 1_000_000L;
                case "cmd" -> {
                    IntegratedServer server = minecraft.getSingleplayerServer();
                    if (server != null) {
                        server.execute(() -> server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER).withSuppressedOutput(), rest));
                    }
                    resumeAt = System.nanoTime() + 300_000_000L;
                }
                case "opt" -> option(minecraft, rest);
                case "res" -> {
                    String[] size = rest.split("x");
                    minecraft.getWindow().setWindowed(Integer.parseInt(size[0]), Integer.parseInt(size[1]));
                    resumeAt = System.nanoTime() + 1_000_000_000L;
                }
                case "spin" -> {
                    spin = Double.parseDouble(rest);
                    spinBase = minecraft.player.getYRot();
                    spinStart = System.nanoTime();
                }
                case "bench" -> {
                    String[] parts = rest.split("\\s+");
                    startBench(parts[0], Double.parseDouble(parts[1]));
                }
                case "mx" -> {
                    Class.forName("com.metallumextra.shader.DebugScript").getMethod("execute", String.class).invoke(null, rest);
                    resumeAt = System.nanoTime() + 300_000_000L;
                }
                case "iris" -> iris(rest);
                case "shot" -> {
                    Path target = OUT.resolve(rest + ".png");
                    Files.createDirectories(OUT);
                    net.minecraft.client.Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> net.minecraft.util.Util.ioPool().execute(() -> {
                        try (image) {
                            image.writeToFile(target);
                        } catch (IOException e) {
                            System.err.println("[perfbench] screenshot failed: " + e);
                        }
                    }));
                    resumeAt = System.nanoTime() + 500_000_000L;
                }
                case "phase" -> phase = rest;
                case "log" -> summary("# " + rest);
                case "quit" -> {
                    quitting = true;
                    summary("# quit");
                    minecraft.stop();
                }
                default -> log("unknown step " + verb);
            }
        } catch (Exception e) {
            summary("# step failed: " + step + ": " + e + (e.getCause() != null ? " / " + e.getCause() : ""));
            e.printStackTrace();
        }
        if (verb.equals("iris") || verb.equals("mx") || verb.equals("opt")) summary(String.format(Locale.ROOT, "# step '%s' blocked the render thread for %.0f ms", step, (System.nanoTime() - began) / 1e6));
    }

    private static void option(final Minecraft minecraft, final String rest) {
        String[] p = rest.split("\\s+");
        var options = minecraft.options;
        switch (p[0]) {
            case "renderDistance" -> options.renderDistance().set(Integer.parseInt(p[1]));
            case "simulationDistance" -> options.simulationDistance().set(Integer.parseInt(p[1]));
            case "vsync" -> options.enableVsync().set(p[1].equals("on"));
            case "fps" -> options.framerateLimit().set(Integer.parseInt(p[1]));
            case "fov" -> options.fov().set(Integer.parseInt(p[1]));
            default -> log("unknown option " + p[0]);
        }
        options.save();
    }

    private static void iris(final String rest) throws Exception {
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        Object config = iris.getMethod("getIrisConfig").invoke(null);
        boolean on = rest.startsWith("on");
        if (on) config.getClass().getMethod("setShaderPackName", String.class).invoke(config, rest.substring(2).trim());
        config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, on);
        config.getClass().getMethod("save").invoke(config);
        iris.getMethod("reload").invoke(null);
        resumeAt = System.nanoTime() + 500_000_000L;
    }

    // ---- a recording ----

    private static void startBench(final String name, final double seconds) {
        benchName = name;
        phase = name;
        frames = 0;
        lastFrameStart = 0;
        benchStart = System.nanoTime();
        benchEnd = benchStart + (long) (seconds * 1e9);
        Runtime.getRuntime().gc();
        gcCount0 = 0;
        gcTime0 = 0;
        for (GarbageCollectorMXBean gc : GCS) {
            gcCount0 += Math.max(0, gc.getCollectionCount());
            gcTime0 += Math.max(0, gc.getCollectionTime());
        }
        procCpu0 = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        heapSum = 0;
        heapN = 0;
        // no further steps run until the recording is over
        resumeAt = Long.MAX_VALUE;
    }

    private static void finishBench() {
        String name = benchName;
        benchName = null;
        int n = frames;
        long span = frameStart - benchStart;
        double seconds = span / 1e9;
        StringBuilder csv = new StringBuilder("frame,interval_ms,body_ms,main_thread_cpu_ms\n");
        double[] ms = new double[n];
        double bodySum = 0, cpuSum = 0;
        double[] bodies = new double[n];
        for (int i = 0; i < n; i++) {
            ms[i] = interval[i] / 1e6;
            bodies[i] = body[i] / 1e6;
            bodySum += bodies[i];
            cpuSum += cpu[i] / 1e6;
            csv.append(i).append(',').append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f%n", ms[i], bodies[i], cpu[i] / 1e6));
        }
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("frames-" + name + ".csv"), csv);
        } catch (IOException e) {
            System.err.println("[perfbench] could not write frames: " + e);
        }
        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        double[] sortedBody = bodies.clone();
        Arrays.sort(sortedBody);
        double median = n == 0 ? 0 : sorted[n / 2];
        int worst1 = Math.max(1, n / 100), worst01 = Math.max(1, n / 1000);
        double low1 = 0, low01 = 0;
        for (int i = 0; i < worst1; i++) low1 += sorted[n - 1 - i];
        for (int i = 0; i < worst01; i++) low01 += sorted[n - 1 - i];
        int stutters = 0, hitches = 0;
        for (double v : ms) {
            if (v > 2 * median && v > 20) stutters++;
            if (v > 100) hitches++;
        }
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : GCS) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcTime += Math.max(0, gc.getCollectionTime());
        }
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double procCpuS = (os.getProcessCpuTime() - procCpu0) / 1e9;
        String line = String.format(Locale.ROOT,
                "%s: %d frames in %.1f s | avg %.1f fps | median %.2f ms (%.1f fps) | p95 %.2f p99 %.2f p99.9 %.2f max %.1f ms | 1%% low %.1f fps | 0.1%% low %.1f fps | "
                        + "stutters(>2x median) %d, hitches(>100ms) %d | frame body avg %.2f ms p95 %.2f | main-thread cpu avg %.2f ms (%.0f%% of body) | process cpu %.2f cores avg | "
                        + "gc %d runs %d ms | heap avg %.0f MB | threads %d",
                name, n, seconds, n / Math.max(seconds, 1e-9), median, 1000.0 / Math.max(median, 1e-9),
                pct(sorted, 0.95), pct(sorted, 0.99), pct(sorted, 0.999), n == 0 ? 0 : sorted[n - 1],
                1000.0 / Math.max(low1 / worst1, 1e-9), 1000.0 / Math.max(low01 / worst01, 1e-9), stutters, hitches,
                bodySum / Math.max(n, 1), pct(sortedBody, 0.95), cpuSum / Math.max(n, 1), 100 * cpuSum / Math.max(bodySum, 1e-9), procCpuS / Math.max(seconds, 1e-9),
                gcCount - gcCount0, gcTime - gcTime0, heapN == 0 ? 0 : heapSum / heapN, THREADS.getThreadCount());
        summary(line);
        resumeAt = 0;
    }

    private static double pct(final double[] sorted, final double q) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))];
    }

    private static void summary(final String line) {
        log(line);
        append("summary.txt", "", line + "\n");
    }

    private static void log(final String text) {
        System.out.println("[perfbench] " + text);
    }
}
