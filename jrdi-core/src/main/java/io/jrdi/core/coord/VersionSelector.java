package io.jrdi.core.coord;

import java.util.Comparator;
import java.util.Objects;

/**
 * Selector between two {@link Gav} values of the same {@code group:artifact}.
 * Picks the {@linkplain #highestVersion winning version} — a semver-style comparison that
 * falls back to lexicographic for non-semver strings.
 */
public final class VersionSelector {

    private static final Comparator<String> SEMVER_AWARE = (a, b) -> {
        int[] pa = parseParts(a);
        int[] pb = parseParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) return Integer.compare(pa[i], pb[i]);
        }
        return 0;
    };

    private VersionSelector() {
    }

    public static String highestVersion(String a, String b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return SEMVER_AWARE.compare(a, b) >= 0 ? a : b;
    }

    public static Gav highestVersion(Gav a, Gav b) {
        if (!a.group().equals(b.group()) || !a.artifact().equals(b.artifact())) {
            throw new IllegalArgumentException("not same coordinate: " + a + " vs " + b);
        }
        return SEMVER_AWARE.compare(a.version(), b.version()) >= 0 ? a : b;
    }

    private static int[] parseParts(String v) {
        int dash = v.indexOf('-');
        String core = dash < 0 ? v : v.substring(0, dash);
        String[] parts = core.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
