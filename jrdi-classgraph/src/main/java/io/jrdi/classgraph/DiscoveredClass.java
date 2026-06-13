package io.jrdi.classgraph;

import java.util.List;

/**
 * One JVM class discovered by {@link ClasspathScanner}. The {@code fqn} is the internal
 * slashed form (e.g. {@code com/acme/Foo}); the {@code accessFlags} bitmask mirrors
 * ASM's {@code Opcodes} (e.g. {@code ACC_PUBLIC=1}, {@code ACC_INTERFACE=0x0200}).
 */
public record DiscoveredClass(
        String fqn,
        int accessFlags,
        String superclassFqn,
        boolean isInterface,
        boolean isAnnotation,
        boolean isEnum,
        boolean isRecord,
        boolean isSealed
) {
    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public String slashedFqn() {
        return fqn.replace('.', '/');
    }
}
