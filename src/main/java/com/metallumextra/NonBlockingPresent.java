package com.metallumextra;

import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import io.github.kokodio.metaljvm.quartzcore.CAMetalDrawable;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Non-blocking present.
 * <p>
 * Metallum calls nextDrawable on the render thread, which blocks whenever macOS has no swapchain image
 * free, even with display sync off. Here a helper thread does the blocking call and parks one drawable in a slot.
 * The render thread takes it if it is there and otherwise skips presenting that frame (Metallum already handles a
 * null drawable), so the render thread never waits on the display. Only used when vsync is off.
 */
public final class NonBlockingPresent {
    private static final ExtraConfig CONFIG = ExtraConfig.get();

    private static volatile boolean displaySyncOff;
    private static Feeder feeder;

    private NonBlockingPresent() {
    }

    /** Called when the swapchain is (re)configured. With vsync on, blocking on the display is the point. */
    public static void setDisplaySyncOff(final boolean off) {
        displaySyncOff = off;
        Feeder f = feeder;
        if (f != null) LockSupport.unpark(f.thread);
    }

    /** Vsync is off and the fix is switched on (it can be toggled while the game runs). */
    private static boolean active() {
        return displaySyncOff && CONFIG.nonBlockingPresent;
    }

    /** Replacement for layer.nextDrawable() in the present path. Render thread only. */
    public static CAMetalDrawable nextDrawable(final CAMetalLayer layer) {
        Feeder f = feeder;
        if (f == null && !active()) return layer.nextDrawable();
        if (f == null || f.layer != layer) {
            if (f != null) f.stop();
            feeder = new Feeder(layer);
            return layer.nextDrawable();
        }
        CAMetalDrawable ready = f.slot.getAndSet(null);
        if (ready != null) {
            LockSupport.unpark(f.thread);
            // Hand our retain over to the caller's autorelease pool, matching what nextDrawable itself returns.
            ready.autorelease();
            FrameProfiler.framePresented(true);
            return ready;
        }
        if (active()) {
            FrameProfiler.framePresented(false);
            return null;
        }
        return layer.nextDrawable();
    }

    private static final class Feeder implements Runnable {
        final CAMetalLayer layer;
        final AtomicReference<CAMetalDrawable> slot = new AtomicReference<>();
        final Thread thread;
        volatile boolean stopped;

        Feeder(final CAMetalLayer layer) {
            this.layer = layer;
            // Keep the layer alive for as long as this thread may call into it.
            layer.retain();
            this.thread = new Thread(this, "metallum-extra-drawables");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void stop() {
            stopped = true;
            LockSupport.unpark(thread);
        }

        @Override
        public void run() {
            while (!stopped) {
                if (!active() || slot.get() != null) {
                    LockSupport.parkNanos(50_000_000L);
                    continue;
                }
                try (AutoreleasePool _ = AutoreleasePool.push()) {
                    CAMetalDrawable drawable = layer.nextDrawable();
                    if (drawable != null) {
                        drawable.retain();
                        slot.set(drawable);
                    }
                }
            }
            CAMetalDrawable left = slot.getAndSet(null);
            if (left != null) left.release();
            layer.release();
        }
    }
}
