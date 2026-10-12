package com.metallumextra;

/** Checks that MetalFX can be reached from Java on this machine: {@code ./gradlew metalFxProbe}. */
public final class MetalFxProbe {
    private MetalFxProbe() {
    }

    public static void main(final String[] args) throws Throwable {
        System.out.print(com.metallumextra.fx.MetalFx.probe());
    }
}
