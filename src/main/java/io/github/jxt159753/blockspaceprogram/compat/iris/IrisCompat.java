package io.github.jxt159753.blockspaceprogram.compat.iris;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

public final class IrisCompat {
    private static final BooleanSupplier SHADER_PACK_IN_USE = shaderPackQuery();

    private IrisCompat() {
    }

    public static boolean isShaderPackInUse() {
        return SHADER_PACK_IN_USE.getAsBoolean();
    }

    private static BooleanSupplier shaderPackQuery() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method query = api.getMethod("isShaderPackInUse");
            return () -> {
                try {
                    return (boolean) query.invoke(instance);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Could not query the Iris shader pack state", e);
                }
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not access the Iris shader pack API", e);
        }
    }
}
