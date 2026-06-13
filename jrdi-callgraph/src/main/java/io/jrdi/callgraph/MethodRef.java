package io.jrdi.callgraph;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;

import java.util.Objects;

/**
 * Stable identifier for a method in the call graph: a pair of {@link Fqn} (the owner class)
 * and {@link MethodKey} (name + erased descriptor). Used as the graph's vertex id.
 */
public record MethodRef(Fqn owner, MethodKey key) {

    public MethodRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
    }

    public String dashed() {
        return owner.slashed() + "#" + key.name() + key.descriptor();
    }

    public static MethodRef of(String ownerSlashed, String name, String desc) {
        return new MethodRef(Fqn.of(ownerSlashed), new MethodKey(name, desc));
    }
}
