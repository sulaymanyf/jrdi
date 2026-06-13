package io.jrdi.classgraph;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a single jar (or directory) with ClassGraph and reports the discovered classes.
 * Each {@link DiscoveredClass} captures the JVM-internal FQN plus its access flags.
 */
public final class ClasspathScanner {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathScanner.class);

    private final boolean acceptPackages;

    public ClasspathScanner() {
        this(false);
    }

    public ClasspathScanner(boolean acceptPackages) {
        this.acceptPackages = acceptPackages;
    }

    public ScanReport scanJar(Path jarPath) {
        try (ScanResult sr = new io.github.classgraph.ClassGraph()
                .overrideClasspath(jarPath)
                .enableClassInfo()
                .scan()) {
            return buildReport(sr);
        }
    }

    public ScanReport scanDirectory(Path root) {
        try (ScanResult sr = new io.github.classgraph.ClassGraph()
                .acceptPathsNonRecursive(root.toString())
                .enableClassInfo()
                .scan()) {
            return buildReport(sr);
        }
    }

    public ScanReport scanFiles(List<Path> jarsAndDirs) {
        String[] paths = jarsAndDirs.stream().map(Path::toString).toArray(String[]::new);
        try (ScanResult sr = new io.github.classgraph.ClassGraph()
                .acceptPaths(paths)
                .acceptJars(paths)
                .enableClassInfo()
                .scan()) {
            return buildReport(sr);
        }
    }

    private ScanReport buildReport(ScanResult sr) {
        ClassInfoList infos = sr.getAllClasses();
        LOG.debug("classgraph found {} classes", infos.size());
        List<DiscoveredClass> out = new ArrayList<>(infos.size());
        for (ClassInfo ci : infos) {
            out.add(new DiscoveredClass(
                    ci.getName(),
                    ci.getModifiers(),
                    ci.getSuperclass() == null ? null : ci.getSuperclass().getName(),
                    ci.isInterface(),
                    ci.isAnnotation(),
                    ci.isEnum(),
                    ci.isRecord(),
                    false
            ));
        }
        return new ScanReport(out);
    }
}
