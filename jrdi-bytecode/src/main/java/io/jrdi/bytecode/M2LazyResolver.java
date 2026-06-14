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
import io.jrdi.core.coord.Gav;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.InvokeRepo;
import io.jrdi.storage.repo.M2Repo;
import io.jrdi.storage.repo.MethodRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
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
 * 0.3.0-M1: extracts class facts from a jar in a configured m2
 * root and writes them into the main {@code classes} /
 * {@code methods} / {@code invokes} tables — not the m2-scoped
 * tables. This means a dependency jar's classes are
 * indistinguishable from the project's own classes in queries;
 * the {@code source_jar} column on {@code classes} records
 * provenance so the LLM can tell them apart.
 */
public final class M2LazyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(M2LazyResolver.class);

    public static final int DEFAULT_CACHE_LIMIT = 50;

    private final Db db;
    private final List<Path> m2Roots;
    private final BytecodePass bytecode;
    private final int cacheLimit;

    public M2LazyResolver(Db db, List<Path> m2Roots) {
        this(db, m2Roots, new BytecodePass(), DEFAULT_CACHE_LIMIT);
    }

    public M2LazyResolver(Db db, List<Path> m2Roots,
                          BytecodePass bytecode, int cacheLimit) {
        this.db = db;
        this.m2Roots = List.copyOf(m2Roots);
        this.bytecode = bytecode;
        this.cacheLimit = cacheLimit;
    }

    public Optional<ClassRepo.Record> resolveClassByFqn(Fqn fqn) {
        Optional<Path> jarPathOpt = findJarContaining(fqn);
        if (jarPathOpt.isEmpty()) {
            return Optional.empty();
        }
        Path jarPath = jarPathOpt.get();
        long cacheId = ensureExtracted(jarPath);
        if (cacheId < 0) return Optional.empty();
        SqliteRepos.m2Repo(db).touchCache(cacheId);
        return SqliteRepos.classRepo(db).findByFqn(fqn);
    }

    public Optional<ClassRepo.Record> resolveImplOf(Fqn interfaceFqn) {
        Optional<Path> jarPathOpt = findJarWithSubclassOf(interfaceFqn);
        if (jarPathOpt.isEmpty()) return Optional.empty();
        Path jarPath = jarPathOpt.get();
        long cacheId = ensureExtracted(jarPath);
        if (cacheId < 0) return Optional.empty();
        SqliteRepos.m2Repo(db).touchCache(cacheId);
        return SqliteRepos.classRepo(db).findByFqn(interfaceFqn);
    }

    public int warmAll() {
        return warmAll(m2Roots);
    }

    public int warmAll(List<Path> roots) {
        int extracted = 0;
        for (Path root : roots) {
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

    public long extractGav(Gav gav) {
        String relPath = gav.group().replace('.', '/') + "/" +
                gav.artifact() + "/" + gav.version() + "/" +
                gav.artifact() + "-" + gav.version() + ".jar";
        for (Path root : m2Roots) {
            Path jar = root.resolve(relPath);
            if (Files.isRegularFile(jar)) {
                return ensureExtracted(jar);
            }
        }
        return -1L;
    }

    private long ensureExtracted(Path jarPath) {
        String path = jarPath.toAbsolutePath().toString();
        synchronized (path.intern()) {
            long mtime;
            String sha;
            try {
                mtime = Files.getLastModifiedTime(jarPath).toMillis();
                sha = sha256(jarPath);
            } catch (IOException e) {
                return -1L;
            }
            Optional<M2Repo.CacheRow> existing = SqliteRepos.m2Repo(db).findCache(path);
            if (existing.isPresent()
                    && existing.get().jarMtimeMs() == mtime
                    && existing.get().jarSha256().equals(sha)) {
                return existing.get().id();
            }
            existing.ifPresent(c -> evictJarRows(c));
            return extractIntoMainTables(jarPath, path, sha, mtime);
        }
    }

    private void evictJarRows(M2Repo.CacheRow cache) {
        try (var c = db.dataSource().getConnection();
             var ps = c.prepareStatement("DELETE FROM classes WHERE source_jar = ?")) {
            ps.setString(1, cache.jarPath());
            int n = ps.executeUpdate();
            LOG.info("m2: evicted {} class rows for {}", n, cache.jarPath());
        } catch (Exception e) {
            LOG.warn("m2: evict failed for {}: {}", cache.jarPath(), e.getMessage());
        }
        SqliteRepos.m2Repo(db).evictCache(cache.id());
    }

    private long extractIntoMainTables(Path jarPath, String path,
                                        String sha, long mtime) {
        LOG.info("m2: extracting {} into main tables", path);
        long cacheId = SqliteRepos.m2Repo(db)
                .upsertCache(path, sha, mtime, 0, 0, 0);
        int classes = 0, methods = 0, invokes = 0;
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        MethodRepo methodRepo = SqliteRepos.methodRepo(db);
        InvokeRepo invokeRepo = SqliteRepos.invokeRepo(db);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.endsWith(".class")) continue;
                String slashed = name.substring(0, name.length() - 6);
                Fqn fqn = Fqn.fromDotted(slashed.replace('/', '.'));
                try (InputStream in = jar.getInputStream(e)) {
                    PassResult pr = bytecode.run(fqn, in.readAllBytes());
                    Fqn superFqn = pr.superFqn().orElse(Fqn.OBJECT);
                    long classId = classRepo.upsertWithSourceJar(fqn,
                            pr.access(),
                            superFqn,
                            null,
                            "",
                            "m2",
                            path);
                    classes++;
                    List<InvokeRepo.Edge> edges = new ArrayList<>();
                    for (var m : pr.methods()) {
                        Integer startLine = m.startLine();
                        Integer endLine = m.endLine();
                        long mid = methodRepo.upsert(classId, m.name(), m.desc(),
                                m.signatureRaw() == null ? "" : m.signatureRaw(),
                                startLine, endLine, m.virtual());
                        for (var inv : pr.invokes()) {
                            String calleeName = inv.calleeName() == null ? "" : inv.calleeName();
                            String calleeDesc = inv.calleeDesc() == null ? "" : inv.calleeDesc();
                            String calleeClass = inv.calleeOwner() == null
                                    ? ""
                                    : inv.calleeOwner().replace('.', '/');
                            edges.add(new InvokeRepo.Edge(
                                    mid,
                                    calleeClass,
                                    calleeName,
                                    calleeDesc,
                                    mapKind(inv.kind()),
                                    inv.line(),
                                    InvokeRepo.Confidence.PROBABLE));
                            invokes++;
                        }
                        methods++;
                    }
                    if (!edges.isEmpty()) {
                        invokeRepo.insertAll(edges);
                    }
                } catch (Exception ex) {
                    LOG.debug("m2: skip class {}: {}", name, ex.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("m2: failed to open {}: {}", path, e.getMessage());
            SqliteRepos.m2Repo(db).evictCache(cacheId);
            return -1L;
        }
        SqliteRepos.m2Repo(db).upsertCache(path, sha, mtime, classes, methods, invokes);
        enforceLimit();
        return SqliteRepos.m2Repo(db).findCache(path).orElseThrow().id();
    }

    private static InvokeRepo.Kind mapKind(PassResult.Kind k) {
        return switch (k) {
            case VIRTUAL -> InvokeRepo.Kind.VIRTUAL;
            case STATIC -> InvokeRepo.Kind.STATIC;
            case INTERFACE -> InvokeRepo.Kind.INTERFACE;
            case SPECIAL -> InvokeRepo.Kind.SPECIAL;
            case DYNAMIC -> InvokeRepo.Kind.DYNAMIC;
            case REFLECT -> InvokeRepo.Kind.REFLECT;
        };
    }

    private void enforceLimit() {
        M2Repo repo = SqliteRepos.m2Repo(db);
        int total = repo.oldestCaches(Integer.MAX_VALUE).size();
        if (total <= cacheLimit) return;
        int toEvict = total - cacheLimit;
        for (M2Repo.CacheRow c : repo.oldestCaches(toEvict)) {
            LOG.info("m2: evicting LRU jar {}", c.jarPath());
            evictJarRows(c);
        }
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
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

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
                ClassInfo first = subs.get(0);
                java.io.File element = first.getClasspathElementFile();
                if (element == null) continue;
                Path elementPath = element.toPath();
                if (elementPath.getFileName().toString().endsWith(".jar")) {
                    return Optional.of(elementPath);
                }
            } catch (Exception e) {
                LOG.debug("m2: subclass scan of {} failed: {}", root, e.getMessage());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ISO_INSTANT;
}
