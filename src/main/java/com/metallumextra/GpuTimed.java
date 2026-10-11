package com.metallumextra;

/** Added to Metal's command buffer (by a mixin): the frame profiler's reading of the buffer's own timestamps. */
public interface GpuTimed {
    void metallumExtra$readGpuTimes();
}
