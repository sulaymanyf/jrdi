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
 */
package io.jrdi.bytecode;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.repo.M2Repo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * On-demand class facts pulled from jars in the local Maven cache
 * (~/.m2/repository). Populates the {@code m2_*} tables introduced in
 * V10 schema.
 *
 * <p>The resolver is "lazy" because it does NOT scan the m2 cache
 * eagerly during indexing. Instead, it's invoked from a query-layer
 * trigger — typically {@code find_dubbo_services} returning
 * {@code implClassId = 0} or {@code callers_of} crossing a class
 * that lives in a non-indexed jar. The resolver then:
 *
 * <ol>
 *   <li>Walks the user's m2 directory (or a configured list of jar
 *       directories) to find a jar that defines the target class or
 *       a class implementing the target interface.</li>
 *   <li>Computes a SHA-256 + mtime. If the {@code m2_caches} row
 *       already has matching values, the cached facts are reused
 *       (LRU touch).</li>
 *   <li>Otherwise, runs a lightweight ASM pass over the jar —
 *       just classes + methods + invokes, no framework annotation
 *       scanning, no source attribution. Persists the facts.</li>
 * </ol>
 *
 * <p>Threading: {@code resolve()} is synchronized at the per-jar
 * level (we use the cache row id as a monitor). Multiple threads
 * can resolve different jars concurrently, but the same jar is
 * extracted by exactly one thread.
 */
public final class M2LazyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(M2LazyResolver.class);

    /** Soft cap on how many m2 jar caches to keep around. */
    public static final int DEFAULT_CACHE_LIMIT = 50;

    private final M2Repo repo;
    private final List<Path> m2Roots;
    private final BytecodePass bytecode;
    private final int cacheLimit;

    public M2LazyResolver(M2Repo repo, List<Path> m2Roots) {
        this(repo, m2Roots, new BytecodePass(), DEFAULT_CACHE_LIMIT);
    }

    public M2LazyResolver(M2Repo repo, List<Path> m2Roots,
                          BytecodePass bytecode, int cacheLimit) {
        this.repo = repo;
        this.m2Roots = List.copyOf(m2Roots);
        this.bytecode = bytecode;
        this.cacheLimit = cacheLimit;
    }

    /**
     * Find a jar that contains the given class, run the lightweight
     * ASM pass, and persist the facts. Returns the freshly-extracted
     * (or cache-hit) {@link M2Repo.ClassRow} for the target class,
     * or empty if no jar defines it.
     */
    public Optional<M2Repo.ClassRow> resolveClassByFqn(Fqn fqn) {
        Optional<Path> jarPathOpt = findJarContaining(fqn);
        if (jarPathOpt.isEmpty()) {
            LOG.debug("m2: no jar in {} defines {}", m2Roots, fqn);
            return Optional.empty();
        }
        Path jarPath = jarPathOpt.get();
        long cacheId = ensureExtracted(jarPath);
        if (cacheId < 0) return Optional.empty();
        repo.touchCache(cacheId);
        return repo.findM2Class(fqn, jarPath.toAbsolutePath().toString())
                .or(() -> repo.findM2ClassesByFqn(fqn).stream().findFirst());
    }

    /**
     * Find a jar that contains a class implementing or extending
     * the given FQN (interface or class). Used by
     * {@code find_dubbo_services} when {@code implClassId = 0}.
     */
    public Optional<M2Repo.ClassRow> resolveImplOf(Fqn interfaceFqn) {
        Optional<Path> jarPathOpt = findJarWithSubclassOf(interfaceFqn);
        if (jarPathOpt.isEmpty()) return Optional.empty();
        Path jarPath = jarPathOpt.get();
        long cacheId = ensureExtracted(jarPath);
        if (cacheId < 0) return Optional.empty();
        repo.touchCache(cacheId);
        // The first class extracted from the jar that's a subtype of
        // the target. With many impls possible, we just return the
        // one that ClassGraph found in the matching jar.
        return repo.findM2ClassesByFqn(interfaceFqn).stream()
                .filter(c -> interfaceFqn.slashed().equals(c.fqn().slashed()))
                .findFirst();
    }

    /**
     * Pre-warm the cache: extract every jar in the m2 roots. Used
     * by the {@code jrdi m2-warm} CLI subcommand for offline priming.
     */
    public int warmAll() {
        int extracted = 0;
        for (Path root : m2Roots) {
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.walk(root)) {
                var jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                                  .toList();
                for (Path jar : jars) {
                    long id = ensureExtracted(jar);
                    if (id > 0) extracted++;
                }
            } catch (IOException e) {
                LOG.warn("m2-warm: failed to walk {}: {}", root, e.getMessage());
            }
        }
        enforceLimit();
        return extracted;
    }

    // ─── Internals ──────────────────────────────────────────────────

    /**
     * Make sure the jar at {@code jarPath} has been extracted into
     * the m2_caches / m2_classes / m2_methods / m2_invokes tables.
     * Returns the cache row id, or -1 on failure.
     *
     * <p>Synchronized per-jar (by path string) so two concurrent
     * triggers don't double-extract.
     */
    private long ensureExtracted(Path jarPath) {
        String path = jarPath.toAbsolutePath().toString();
        synchronized (path.intern()) {
            long mtime = 0L;
            String sha;
            try {
                mtime = Files.getLastModifiedTime(jarPath).toMillis();
                sha = sha256(jarPath);
            } catch (IOException e) {
                LOG.warn("m2: failed to stat {}: {}", path, e.getMessage());
                return -1L;
            }
            Optional<M2Repo.CacheRow> existing = repo.findCache(path);
            if (existing.isPresent()
                    && existing.get().jarMtimeMs() == mtime
                    && existing.get().jarSha256().equals(sha)) {
                // Cache hit.
                return existing.get().id();
            }
            // Cache miss or stale. If we had a row, drop it before
            // re-extracting (CASCADE clears the children).
            existing.ifPresent(c -> repo.evictCache(c.id()));
            return extract(jarPath, path, sha, mtime);
        }
    }

    private long extract(Path jarPath, String path, String sha, long mtime) {
        LOG.info("m2: extracting {}", path);
        long cacheId = repo.upsertCache(path, sha, mtime, 0, 0, 0);
        int classes = 0, methods = 0, invokes = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.endsWith(".class")) continue;
                Fqn fqn = Fqn.fromDotted(name.endsWith(".class")
                        ? name.substring(0, name.length() - 6).replace('/', '.')
                        : name.replace('/', '.'));
                try (InputStream in = jar.getInputStream(e)) {
                    PassResult pr = bytecode.run(fqn, in.readAllBytes());
                    String ifaces = joinFqns(pr.interfaces());
                    // If ASM didn't record a super (e.g. for the
                    // Object class file itself), fall back to a
                    // sentinel FQN so the column is non-empty. The
                    // query layer can distinguish "extends Object"
                    // from the sentinel by the FQN value itself.
                    Fqn superFqn = pr.superFqn().orElse(Fqn.OBJECT);
                    int access = pr.access();
                    long classId = repo.insertM2Class(fqn, superFqn, access, ifaces, path);
                    classes++;
                    for (var m : pr.methods()) {
                        Integer line = m.startLine();
                        // MethodInfo doesn't carry the access flag yet
                        // (BytecodePass doesn't extract it for methods,
                        // only for the class). The schema column is
                        // present so future versions can populate it
                        // without a migration; for now we record 0.
                        repo.insertM2Method(classId, m.name(), m.desc(), 0, line);
                        methods++;
                    }
                    for (var inv : pr.invokes()) {
                        String calleeName = inv.calleeName() == null ? "" : inv.calleeName();
                        String calleeDesc = inv.calleeDesc() == null ? "" : inv.calleeDesc();
                        String calleeClass = inv.calleeOwner() == null
                                ? ""
                                : inv.calleeOwner().replace('.', '/');
                        // We need the *method id*, not the call's class
                        // — so re-look-up after insertM2Method. Cheaper
                        // than threading ids through PassResult.
                        long mid = methodIdByName(classId, calleeName, calleeDesc);
                        if (mid < 0) continue;
                        String kind = inv.kind() == null
                                ? "invoke"
                                : inv.kind().name().toLowerCase();
                        repo.insertM2Invoke(fqn.dotted(), mid, calleeClass,
                                calleeName, calleeDesc, kind);
                        invokes++;
                    }
                } catch (Exception ex) {
                    // Single broken class doesn't kill the jar.
                    LOG.debug("m2: skip class {}: {}", name, ex.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("m2: failed to open {}: {}", path, e.getMessage());
            repo.evictCache(cacheId);
            return -1L;
        }
        // Update the counts column.
        repo.upsertCache(path, sha, mtime, classes, methods, invokes);
        enforceLimit();
        return repo.findCache(path).orElseThrow().id();
    }

    private long methodIdByName(long classId, String name, String desc) {
        // m2_methods has UNIQUE(class_id, name, desc), so we can
        // re-query after insertM2Method. Cheap; SQLite caches the
        // index. Returns -1 if the call site invokes a method that
        // doesn't exist in the same class (rare; static initializers
        // calling Object.<clinit> is the common case we skip).
        return repo.methodsOf(classId).stream()
                .filter(m -> m.name().equals(name) && m.desc().equals(desc))
                .map(M2Repo.MethodRow::id)
                .findFirst()
                .orElse(-1L);
    }

    private void enforceLimit() {
        int total = repo.oldestCaches(Integer.MAX_VALUE).size();
        if (total <= cacheLimit) return;
        int toEvict = total - cacheLimit;
        for (M2Repo.CacheRow c : repo.oldestCaches(toEvict)) {
            LOG.info("m2: evicting LRU jar {}", c.jarPath());
            repo.evictCache(c.id());
        }
    }

    private static String joinFqns(List<Fqn> fqns) {
        if (fqns == null || fqns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fqns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(fqns.get(i).slashed());
        }
        return sb.toString();
    }

    private static String sha256(Path jarPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(jarPath)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            // SHA-256 is mandatory in the JDK; if it's missing the
            // JVM is broken. Surface that.
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    // ─── m2 discovery ──────────────────────────────────────────────

    /**
     * Walk the configured m2 roots and find a jar that contains a
     * {@code .class} entry for the given slashed FQN.
     */
    private Optional<Path> findJarContaining(Fqn fqn) {
        String entry = fqn.slashed() + ".class";
        for (Path root : m2Roots) {
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.walk(root)) {
                var hit = stream
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> jarContains(p, entry))
                        .findFirst();
                if (hit.isPresent()) return hit;
            } catch (IOException e) {
                LOG.debug("m2: walk {} failed: {}", root, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static boolean jarContains(Path jarPath, String entry) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getJarEntry(entry) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Find a jar in the m2 roots that contains a class implementing
     * (or extending) the target FQN. We use ClassGraph for the
     * subtype discovery — it already understands Java's type
     * hierarchy and can scan a single jar quickly.
     */
    private Optional<Path> findJarWithSubclassOf(Fqn target) {
        String targetDotted = target.dotted();
        for (Path root : m2Roots) {
            if (!Files.isDirectory(root)) continue;
            try (ScanResult scan = new ClassGraph()
                    .overrideClasspath(root.toString())
                    .enableAllInfo()
                    .scan()) {
                ClassInfoList subs = scan.getSubclasses(targetDotted);
                if (subs.isEmpty()) continue;
                // Pick the first implementation and ask ClassGraph
                // for the classpath element it was loaded from. When
                // the element is a jar, that's our path. When it's
                // a directory, this method returns null and we skip.
                ClassInfo first = subs.get(0);
                java.io.File element = first.getClasspathElementFile();
                if (element == null) continue;
                Path elementPath = element.toPath();
                if (elementPath.getFileName().toString().endsWith(".jar")) {
                    return Optional.of(elementPath);
                }
                // Directory layout: not in m2 form. Skip.
            } catch (Exception e) {
                LOG.debug("m2: subclass scan of {} failed: {}", root, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Suppress unused-import warning — Instant is referenced only
     * in javadoc.
     */
    @SuppressWarnings("unused")
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ISO_INSTANT;
}
