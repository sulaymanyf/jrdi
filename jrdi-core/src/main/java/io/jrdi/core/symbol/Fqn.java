package io.jrdi.core.symbol;

import java.util.Objects;

/**
 * Immutable value object for a fully-qualified Java class name (slashed, JVM-internal form).
 * Examples: {@code java/lang/String}, {@code org/springframework/web/bind/annotation/RestController}.
 */
public final class Fqn {

    public static final Fqn OBJECT = new Fqn("java/lang/Object");

    private final String value;

    public Fqn(String value) {
        Objects.requireNonNull(value, "fqn");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("fqn must not be empty");
        }
        this.value = value;
    }

    public static Fqn of(String value) {
        return new Fqn(value);
    }

    /**
     * Convert dotted form ({@code com.acme.Foo}) to slashed form ({@code com/acme/Foo}).
     * If already slashed, returned unchanged.
     */
    public static Fqn fromDotted(String dotted) {
        if (dotted.indexOf('/') >= 0) {
            return new Fqn(dotted);
        }
        return new Fqn(dotted.replace('.', '/'));
    }

    public String slashed() {
        return value;
    }

    public String dotted() {
        return value.replace('/', '.');
    }

    public String binaryName() {
        int lastSlash = value.lastIndexOf('/');
        return lastSlash < 0 ? value : value.substring(lastSlash + 1);
    }

    public String packageName() {
        int lastSlash = value.lastIndexOf('/');
        return lastSlash < 0 ? "" : value.substring(0, lastSlash).replace('/', '.');
    }

    public boolean isInPackage(String dottedPackage) {
        String slashedPackage = dottedPackage.replace('.', '/');
        if (slashedPackage.isEmpty()) {
            return true;
        }
        return value.equals(slashedPackage)
                || value.startsWith(slashedPackage + "/");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fqn fqn)) return false;
        return value.equals(fqn.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
