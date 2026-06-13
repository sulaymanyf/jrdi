package io.jrdi.classgraph;

import java.util.List;

/**
 * Aggregate output of a {@link ClasspathScanner} run: a flat list of discovered classes.
 * Further analyzers (U3 ASM) will enrich each entry with methods/fields/invokes.
 */
public record ScanReport(List<DiscoveredClass> classes) {

    public int count() {
        return classes.size();
    }
}
