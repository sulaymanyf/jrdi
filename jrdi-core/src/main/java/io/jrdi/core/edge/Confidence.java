package io.jrdi.core.edge;

/**
 * Confidence levels attached to every edge emitted by analyzers. The semantic is:
 * <ul>
 *   <li>{@link #CERTAIN}: bytecode-level direct call, no dynamic dispatch ambiguity.</li>
 *   <li>{@link #PROBABLE}: static-resolvable but with runtime ambiguity (CHA, framework routing, generic-erased match).</li>
 *   <li>{@link #UNCERTAIN}: reflection, dynamic proxy, non-constant argument, conditional framework setup.</li>
 * </ul>
 */
public enum Confidence {
    CERTAIN(3),
    PROBABLE(2),
    UNCERTAIN(1);

    private final int weight;

    Confidence(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public Confidence atLeast(Confidence other) {
        return this.weight >= other.weight ? this : other;
    }

    public Confidence max(Confidence other) {
        return this.weight >= other.weight ? this : other;
    }
}
