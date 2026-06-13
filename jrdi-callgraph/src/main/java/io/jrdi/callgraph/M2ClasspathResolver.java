/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package io.jrdi.callgraph;

import io.jrdi.core.symbol.Fqn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Reads class files from the local Maven cache (~/.m2/repository or any directory of jars)
 * to resolve types that are referenced from indexed code but not themselves in the
 * {@code classes} table. Used by {@link ChaResolver} as a fallback during cross-jar
 * virtual/interface invoke expansion.
 *
 * <p>The implementation:
 * <ol>
 *   <li>On first miss, walks the configured root directories looking for any jar
 *       that contains a {@code .class} entry matching the FQN. Most projects have
 *       their dependencies in {@code ~/.m2/repository}.</li>
 *   <li>Reads only the constant pool + class header of the matching {@code .class} entry
 *       via a minimal ASM scan (no method body parsing). This is fast (single-digit ms
 *       per class).</li>
 *   <li>Caches results in a {@code ConcurrentHashMap} so each FQN is read at most once
 *       per resolver instance.</li>
 * </ol>
 *
 * <p>Lookup cost is bounded by the number of jars in the search roots; with the default
 * m2 cache (~5k jars) a single cold miss takes 50-200ms. Subsequent hits are O(1).
 */
public final class M2ClasspathResolver implements ExternalClassResolver {

    private static final Logger LOG = LoggerFactory.getLogger(M2ClasspathResolver.class);

    private final List<Path> searchRoots;
    private final Map<Fqn, Optional<ExternalClass>> cache = new ConcurrentHashMap<>();

    public M2ClasspathResolver(List<Path> searchRoots) {
        this.searchRoots = searchRoots == null ? List.of() : List.copyOf(searchRoots);
    }

    /** Convenience: default m2 root at {@code ~/.m2/repository} (resolved lazily). */
    public static M2ClasspathResolver defaultM2() {
        return new M2ClasspathResolver(List.of(defaultM2Root()));
    }

    /** {@code user.home/.m2/repository}, or null if {@code user.home} is unset. */
    public static Path defaultM2Root() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        return Path.of(home, ".m2", "repository");
    }

    @Override
    public Optional<ExternalClass> resolve(Fqn fqn) {
        if (searchRoots.isEmpty()) return Optional.empty();
        return cache.computeIfAbsent(fqn, this::findInClasspath);
    }

    private Optional<ExternalClass> findInClasspath(Fqn fqn) {
        String entry = fqn.slashed() + ".class";
        for (Path root : searchRoots) {
            if (!Files.isDirectory(root)) continue;
            Optional<ExternalClass> hit = walkForClass(root, entry);
            if (hit.isPresent()) return hit;
        }
        LOG.debug("external class not found in classpath: {}", fqn);
        return Optional.empty();
    }

    private Optional<ExternalClass> walkForClass(Path root, String entry) {
        // Use a stream so we don't materialize a huge list of jars.
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .map(p -> tryRead(p, entry))
                    .filter(Optional::isPresent)
                    .findFirst()
                    .orElse(Optional.empty());
        } catch (IOException e) {
            LOG.debug("walk failed on {}: {}", root, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ExternalClass> tryRead(Path jar, String entry) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry je = jf.getJarEntry(entry);
            if (je == null) return Optional.empty();
            try (InputStream in = jf.getInputStream(je)) {
                return Optional.of(parseClassHeader(in.readAllBytes()));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Parse just the constant pool + class header with ASM. We don't need method bodies
     * to record method signatures.
     */
    private ExternalClass parseClassHeader(byte[] bytes) {
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
        Fqn[] classRef = new Fqn[1];
        Fqn[] superRef = new Fqn[1];
        List<Fqn> ifaces = new ArrayList<>();
        List<MethodSig> methods = new ArrayList<>();
        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                classRef[0] = Fqn.of(name.replace('.', '/'));
                if (superName != null) superRef[0] = Fqn.of(superName.replace('.', '/'));
                if (interfaces != null) {
                    for (String i : interfaces) ifaces.add(Fqn.of(i.replace('.', '/')));
                }
            }
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                // Skip <clinit> to keep the list focused; record everything else.
                if (!"<clinit>".equals(name)) {
                    methods.add(new MethodSig(name, descriptor, access));
                }
                return null;
            }
        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return new ExternalClass(classRef[0], superRef[0],
                Collections.unmodifiableList(ifaces),
                Collections.unmodifiableList(methods));
    }
}
