package com.metallumextra.shader.pack;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What {@link ShaderPack#load} returned, kept for as long as the pack lives and no option has changed. Reading a program (its includes, the
 * options written in, the conditions decided, old GLSL translated) takes from a tenth of a second to nearly a second for a big pack, and the same
 * program is asked for by the plan of every dimension, by the check when the pack is applied and by every pipeline that uses it.
 */
final class LoadCache {
    private static final Map<ShaderPack, Map<String, String>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static int stamp = -1;

    private LoadCache() {
    }

    static String get(final ShaderPack pack, final String path) {
        Map<String, String> files;
        synchronized (CACHE) {
            int version = PackOptions.version() * 31 + IrisUniforms.customStamp();
            if (version != stamp) {
                CACHE.clear();
                stamp = version;
            }
            files = CACHE.computeIfAbsent(pack, p -> new ConcurrentHashMap<>());
        }
        String hit = files.get(path);
        if (hit != null) return hit;
        String text = pack.loadUncached(path);
        files.put(path, text);
        return text;
    }
}
