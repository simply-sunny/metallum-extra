package com.metallumextra.voxy.gl;

import org.lwjgl.opengl.GLCapabilities;

import java.lang.reflect.Field;

/**
 * Builds the permissive {@link GLCapabilities} instance that {@link
 * com.metallumextra.mixin.voxy.CapabilitiesMixin} feeds to Voxy's capability probe under Metal.
 *
 * <p>Voxy's {@code Capabilities} constructor reads a handful of plain fields off the object LWJGL
 * would have returned ({@code glDispatchComputeIndirect}, {@code GL_KHR_shader_subgroup}, ...). It
 * never dereferences the function-pointer fields, so an instance allocated without running LWJGL's
 * (GL-context bound) constructor is safe as long as those few decision fields carry permissive
 * values: compute + indirect draws + subgroups advertised, everything else at its default of
 * false/0. The instance fields are final, so they are written via reflection.</p>
 */
public final class VoxyShimmedGlCapabilities {
    private static GLCapabilities instance;

    private VoxyShimmedGlCapabilities() {
    }

    /** The shared permissive capabilities object; created on first use, which is GL-free. */
    public static GLCapabilities get() {
        GLCapabilities caps = instance;
        if (caps == null) {
            caps = create();
            instance = caps;
        }
        return caps;
    }

    private static GLCapabilities create() {
        try {
            // sun.misc.Unsafe: allocate without running GLCapabilities' package-private,
            // context-bound constructor, then write the final decision fields directly.
            final Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            final Object unsafe = theUnsafe.get(null);
            final Class<?> unsafeClass = unsafe.getClass();

            final GLCapabilities caps = (GLCapabilities) unsafeClass
                    .getMethod("allocateInstance", Class.class).invoke(unsafe, GLCapabilities.class);

            // Advertise the extensions/features Voxy's probe turns into its booleans; function
            // pointers stay 0 because the probe only null-checks them, and Voxy's GL renderer is
            // disabled on Metal anyway.
            putLong(unsafe, unsafeClass, caps, "glDispatchComputeIndirect", 1L);
            putLong(unsafe, unsafeClass, caps, "glMultiDrawElementsIndirectCountARB", 1L);
            putBoolean(unsafe, unsafeClass, caps, "GL_KHR_shader_subgroup", true);
            return caps;
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("[Metallum Extra] Could not build shimmed GL capabilities", e);
        }
    }

    private static void putLong(final Object unsafe, final Class<?> unsafeClass, final GLCapabilities caps,
                                final String fieldName, final long value) throws ReflectiveOperationException {
        final long offset = (Long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                .invoke(unsafe, GLCapabilities.class.getField(fieldName));
        unsafeClass.getMethod("putLong", Object.class, long.class, long.class).invoke(unsafe, caps, offset, value);
    }

    private static void putBoolean(final Object unsafe, final Class<?> unsafeClass, final GLCapabilities caps,
                                   final String fieldName, final boolean value) throws ReflectiveOperationException {
        final long offset = (Long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                .invoke(unsafe, GLCapabilities.class.getField(fieldName));
        unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(unsafe, caps, offset, value);
    }
}
