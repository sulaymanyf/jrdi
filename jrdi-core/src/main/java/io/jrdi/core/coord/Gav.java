package io.jrdi.core.coord;

import java.util.Comparator;
import java.util.Objects;

/**
 * Maven group / artifact / version coordinate.
 */
public final class Gav implements Comparable<Gav> {

    private static final Comparator<Gav> NATURAL = Comparator
            .comparing(Gav::group)
            .thenComparing(Gav::artifact)
            .thenComparing(Gav::version);

    private final String group;
    private final String artifact;
    private final String version;

    public Gav(String group, String artifact, String version) {
        this.group = Objects.requireNonNull(group);
        this.artifact = Objects.requireNonNull(artifact);
        this.version = Objects.requireNonNull(version);
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) {
            throw new IllegalArgumentException("group, artifact and version must be non-empty");
        }
    }

    public static Gav of(String group, String artifact, String version) {
        return new Gav(group, artifact, version);
    }

    public static Gav parse(String gav) {
        int firstColon = gav.indexOf(':');
        int secondColon = gav.indexOf(':', firstColon + 1);
        if (firstColon < 0 || secondColon < 0) {
            throw new IllegalArgumentException("invalid GAV: " + gav);
        }
        return new Gav(gav.substring(0, firstColon),
                gav.substring(firstColon + 1, secondColon),
                gav.substring(secondColon + 1));
    }

    public String group() {
        return group;
    }

    public String artifact() {
        return artifact;
    }

    public String version() {
        return version;
    }

    public String path(String repoRoot) {
        return repoRoot + "/" + group.replace('.', '/') + "/" + artifact + "/" + version;
    }

    @Override
    public int compareTo(Gav other) {
        return NATURAL.compare(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Gav gav)) return false;
        return group.equals(gav.group) && artifact.equals(gav.artifact) && version.equals(gav.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group, artifact, version);
    }

    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version;
    }
}
