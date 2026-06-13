package io.jrdi.core.symbol;

import java.util.Objects;

/**
 * Stable identifier for a method. Encodes {@code (name, parameterDescriptors)} where descriptors
 * are the JVM-level erased type descriptors (slashed form), e.g. {@code (Ljava/util/List;Ljava/lang/String;)V}.
 * <p>
 * Two method declarations with the same erased signature map to the same {@code MethodKey} regardless
 * of generic instantiations. This is the canonical primary key for the {@code methods} table.
 */
public final class MethodKey {

    private final String name;
    private final String descriptor;
    private final int hashCode;

    public MethodKey(String name, String descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        this.name = name;
        this.descriptor = descriptor;
        this.hashCode = name.hashCode() * 31 + descriptor.hashCode();
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    /**
     * For example {@code org/acme/Foo#bar(Ljava/util/List;)V}.
     */
    public String asString(Fqn owner) {
        return owner.slashed() + "#" + name + descriptor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodKey that)) return false;
        return name.equals(that.name) && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return name + descriptor;
    }
}
