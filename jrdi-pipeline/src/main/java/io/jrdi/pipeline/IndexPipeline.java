package io.jrdi.pipeline;

import io.jrdi.bytecode.PassResult;
import io.jrdi.classgraph.JarClassLister;
import io.jrdi.core.edge.Confidence;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.decompile.CfrDecompiler;
import io.jrdi.decompile.VirtualLineAssigner;
import io.jrdi.resolver.ArtifactFetcher;
import io.jrdi.resolver.FetchResult;
import io.jrdi.resolver.MavenSettingsParser;
import io.jrdi.resolver.ResolverSession;
import io.jrdi.source.MethodMatcher;
import io.jrdi.source.SourceLoader;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.ArtifactRepo;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.FieldRepo;
import io.jrdi.storage.repo.FileRepo;
import io.jrdi.storage.repo.InvokeRepo;
import io.jrdi.storage.repo.IssueRepo;
import io.jrdi.storage.repo.LambdaRepo;
import io.jrdi.storage.repo.MethodRepo;
import io.jrdi.storage.repo.RepoRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.apache.maven.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Top-level indexing pipeline. Given a list of GAVs and a configured Db, it:
 * <ol>
 *   <li>Resolves each GAV via the {@link ArtifactFetcher} (or accepts an in-place jar path).</li>
 *   <li>Lists the classes in the jar via {@link JarClassLister}.</li>
 *   <li>For each class:
 *     <ol>
 *       <li>Reads the .class bytes, runs {@link io.jrdi.bytecode.BytecodePass}.</li>
 *       <li>Persists the resulting {@link PassResult} (classes / methods / fields / invokes / lambdas).</li>
 *       <li>Enriches line numbers from {@link io.jrdi.source.MethodMatcher} if sources.jar is available,
 *           or from {@link VirtualLineAssigner} (CFR fallback) otherwise.</li>
 *     </ol>
 *   </li>
 *   <li>Records issues for missing sources, unresolved reflect, etc.</li>
 * </ol>
 *
 * <p>The pipeline writes everything to a single SQLite transaction per artifact so a mid-run
 * crash leaves the database coherent.
 *
 * <p>For each class with available source code, the P2 framework analyzers are also run
 * (Spring / Dubbo / MyBatis). They record framework facts (bean declarations, @Autowired
 * sites, Dubbo services, MyBatis statements) into dedicated tables. On classes without
 * any framework annotations the passes are no-ops.
 */
public final class IndexPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(IndexPipeline.class);

    private final io.jrdi.spring.SpringPass springPass;
    private final io.jrdi.spring.SpringXmlPass springXmlPass;
    private final io.jrdi.spring.SpringBootAutoconfigPass springBootAutoconfigPass;
    private final io.jrdi.spring.SpringConditionalPass springConditionalPass;
    private final io.jrdi.dubbo.DubboPass dubboPass;
    private final io.jrdi.dubbo.DubboXmlPass dubboXmlPass;
    private final io.jrdi.mybatis.MybatisPass mybatisPass;
    private final io.jrdi.mybatis.MybatisXmlPass mybatisXmlPass;
    private final io.jrdi.source.AstBuilder astBuilder;

    private final Db db;
    private final ResolverSession session;
    private final ArtifactFetcher fetcher;
    private final JarClassLister lister;
    private final SourceLoader sourceLoader;
    private final MethodMatcher methodMatcher;
    private final VirtualLineAssigner virtualLineAssigner;
    private final io.jrdi.bytecode.BytecodePass bytecodePass;

    public IndexPipeline(Db db, Settings settings) {
        this.db = db;
        this.session = ResolverSession.of(settings);
        this.fetcher = session.fetcher();
        this.lister = new JarClassLister();
        this.sourceLoader = new SourceLoader();
        this.methodMatcher = new MethodMatcher();
        this.virtualLineAssigner = new VirtualLineAssigner();
        this.bytecodePass = new io.jrdi.bytecode.BytecodePass();
        // P2 framework analyzers (no-ops on classes without their annotations)
        this.astBuilder = new io.jrdi.source.AstBuilder();
        this.springPass = new io.jrdi.spring.SpringPass(db, astBuilder);
        this.springXmlPass = new io.jrdi.spring.SpringXmlPass(db);
        this.springBootAutoconfigPass = new io.jrdi.spring.SpringBootAutoconfigPass(db);
        this.springConditionalPass = new io.jrdi.spring.SpringConditionalPass(db);
        this.dubboPass = new io.jrdi.dubbo.DubboPass(db, astBuilder);
        this.dubboXmlPass = new io.jrdi.dubbo.DubboXmlPass(db);
        this.mybatisPass = new io.jrdi.mybatis.MybatisPass(db, astBuilder);
        this.mybatisXmlPass = new io.jrdi.mybatis.MybatisXmlPass(db);
    }

    /** Convenience: build a pipeline from default Maven settings. */
    public static IndexPipeline defaults(Db db) {
        return new IndexPipeline(db, MavenSettingsParser.load());
    }

    public IndexReport indexRepo(String repoName, String rootPath, List<ArtifactInput> artifacts) {
        long start = System.nanoTime();
        RepoRepo repoRepo = SqliteRepos.repoRepo(db);
        ArtifactRepo artifactRepo = SqliteRepos.artifactRepo(db);
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        MethodRepo methodRepo = SqliteRepos.methodRepo(db);
        FieldRepo fieldRepo = SqliteRepos.fieldRepo(db);
        InvokeRepo invokeRepo = SqliteRepos.invokeRepo(db);
        LambdaRepo lambdaRepo = SqliteRepos.lambdaRepo(db);
        IssueRepo issueRepo = SqliteRepos.issueRepo(db);
        FileRepo fileRepo = SqliteRepos.fileRepo(db);

        long repoId = repoRepo.upsert(repoName, rootPath, null, Instant.now().toString());
        int classes = 0, skipped = 0, methods = 0, fields = 0, invokes = 0, lambdas = 0, issues = 0;
        int springBeans = 0, springInjects = 0, dubboServices = 0, dubboReferences = 0, mybatisStatements = 0;
        int dubboXmlServices = 0, dubboXmlReferences = 0, dubboXmlFiles = 0, dubboMethodConfigs = 0;
        int dubboRegistries = 0;
        int mybatisXmlStatements = 0, mybatisXmlFiles = 0, mybatisResultMaps = 0;
        int springXmlBeans = 0, springXmlFiles = 0;
        int springBootAutoconfigs = 0;
        int springAutoconfigConditions = 0;
        Map<String, Integer> perArtifact = new HashMap<>();

        for (ArtifactInput input : artifacts) {
            Path jar = input.jarPath();
            long artifactId = artifactRepo.upsert(
                    repoId, input.gav(), null,
                    input.hasSources(), jar.toString(), 100);

            List<String> classSlashed;
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                classSlashed = lister.list(jar);
            } catch (IOException e) {
                LOG.error("failed to read jar {}: {}", jar, e.getMessage());
                issueRepo.record("jar_read", input.gav().toString(), e.getMessage(),
                        IssueRepo.Severity.ERROR, Instant.now().toString());
                continue;
            }
            int perArtClasses = 0;
            int perArtSkipped = 0;
            // Pass 1: bytecode + line numbers for every class. Populates
            // classes (including `interfaces`), methods, fields, invokes, lambdas,
            // and the per-class file hash. Framework passes (Spring/Dubbo/MyBatis)
            // need a complete picture of the indexed classes — including the
            // `interfaces` column — to resolve cross-class candidates, so they
            // run in a second pass below.
            for (String slashed : classSlashed) {
                Fqn owner = Fqn.of(slashed);
                try {
                    ClassVisitStats stats = processOneClass(
                            owner, jar, input.sourcesPath(), artifactId,
                            classRepo, methodRepo, fieldRepo,
                            invokeRepo, lambdaRepo, issueRepo, fileRepo);
                    classes += stats.classes();
                    skipped += stats.skipped();
                    methods += stats.methods();
                    fields += stats.fields();
                    invokes += stats.invokes();
                    lambdas += stats.lambdas();
                    issues += stats.issues();
                    perArtClasses += stats.classes();
                    perArtSkipped += stats.skipped();
                } catch (Exception e) {
                    LOG.error("class {} failed: {}", owner, e.toString(), e);
                    issueRepo.record("class_process", owner.slashed(), e.toString(),
                            IssueRepo.Severity.ERROR, Instant.now().toString());
                    issues++;
                }
            }
            // Pass 2: framework passes (Spring / Dubbo / MyBatis). They run after
            // all classes in the artifact are recorded so that candidates
            // resolution can see the full class set, including the implemented
            // interfaces that were recorded in pass 1.
            //
            // Sub-pass 2a: record Spring beans for every class. We need to do
            // this for the whole artifact before resolving any @Autowired sites,
            // because a class's injects might need to see beans declared on a
            // sibling class that's later in the iteration order.
            for (String slashed : classSlashed) {
                Fqn owner = Fqn.of(slashed);
                String sourceText = input.sourcesPath()
                        .flatMap(p -> sourceLoader.read(p, owner))
                        .orElse(null);
                if (sourceText == null || sourceText.isBlank()) continue;
                int recorded = springPass.recordBeans(sourceText, owner);
                springBeans += recorded;
            }
            // Sub-pass 2b: resolve @Autowired sites against the now-complete bean set.
            for (String slashed : classSlashed) {
                Fqn owner = Fqn.of(slashed);
                String sourceText = input.sourcesPath()
                        .flatMap(p -> sourceLoader.read(p, owner))
                        .orElse(null);
                if (sourceText == null || sourceText.isBlank()) continue;
                int resolved = springPass.resolveInjects(sourceText, owner);
                springInjects += resolved;
            }
            // Other framework passes (Dubbo, MyBatis) still go per-class.
            for (String slashed : classSlashed) {
                Fqn owner = Fqn.of(slashed);
                FrameworkStats fs = runOtherFrameworkPasses(owner, input.sourcesPath());
                dubboServices += fs.dubboServices();
                dubboReferences += fs.dubboReferences();
                mybatisStatements += fs.mybatisStatements();
            }

            // XML framework passes: scan non-class resources in the main jar for
            // Spring XML configs, Dubbo Spring configs and MyBatis Mapper.xml files.
            // These run LAST because they have no class-level dependencies — we
            // just need the (fully populated) framework tables to upsert into.
            // Each pass is idempotent via the V3/V4 ON CONFLICT indexes, so a
            // re-index of an unchanged artifact is a cheap read-then-no-op.
            //
            // SpringXmlPass runs FIRST inside the XML block so that DubboXmlPass
            // can auto-join ref="someBean" → impl_class_id via spring_beans.
            try {
                io.jrdi.spring.SpringXmlPass.Result sr = springXmlPass.scanJar(jar);
                springXmlBeans += sr.beansRecorded();
                springXmlFiles += sr.filesScanned();
            } catch (Exception e) {
                LOG.debug("spring xml pass failed on {}: {}", jar, e.getMessage());
            }
            // Spring Boot auto-config (META-INF/spring.factories + AutoConfiguration.imports)
            try {
                io.jrdi.spring.SpringBootAutoconfigPass.Result sb = springBootAutoconfigPass.scanJar(jar);
                springBootAutoconfigs += sb.autoconfigsRecorded();
            } catch (Exception e) {
                LOG.debug("spring boot autoconfig pass failed on {}: {}", jar, e.getMessage());
            }
            // V6: Spring Boot auto-config condition analysis. Reads every
            // auto-config class from V5, walks its @Conditional* annotations
            // via ASM, and records the conditions in spring_autoconfig_conditions.
            // This is repo-wide (not per-jar) because the V5 table is global.
            // For now we run it once at the end of indexing each artifact; the
            // pass itself is idempotent via V5+V6 UNIQUE indexes.
            try {
                io.jrdi.spring.SpringConditionalPass.Result sc = springConditionalPass.scanAll();
                springAutoconfigConditions += sc.conditionsRecorded();
            } catch (Exception e) {
                LOG.debug("spring conditional pass failed on {}: {}", jar, e.getMessage());
            }
            try {
                io.jrdi.dubbo.DubboXmlPass.Result dr = dubboXmlPass.scanJar(jar);
                dubboXmlServices    += dr.servicesRecorded();
                dubboXmlReferences  += dr.referencesRecorded();
                dubboMethodConfigs  += dr.methodConfigsRecorded();
                dubboRegistries     += dr.registriesRecorded();
                dubboXmlFiles       += dr.filesScanned();
            } catch (Exception e) {
                LOG.debug("dubbo xml pass failed on {}: {}", jar, e.getMessage());
            }
            try {
                io.jrdi.mybatis.MybatisXmlPass.Result mr = mybatisXmlPass.scanJar(jar);
                mybatisXmlStatements += mr.statementsRecorded();
                mybatisResultMaps    += mr.resultMapsRecorded();
                mybatisXmlFiles      += mr.filesScanned();
            } catch (Exception e) {
                LOG.debug("mybatis xml pass failed on {}: {}", jar, e.getMessage());
            }

            if (perArtSkipped > 0) {
                LOG.info("indexed {} classes ({} unchanged) from {}", perArtClasses, perArtSkipped, input.gav());
            }
            perArtifact.put(input.gav().toString(), perArtClasses);
            LOG.info("indexed {} classes from {}", perArtClasses, input.gav());
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        return new IndexReport(repoId, elapsed, artifacts.size(), classes, skipped, methods,
                fields, invokes, lambdas, issues, perArtifact,
                springBeans, springInjects,
                dubboServices, dubboReferences, mybatisStatements,
                dubboXmlServices, dubboXmlReferences, mybatisXmlStatements,
                dubboMethodConfigs, mybatisResultMaps,
                springXmlBeans, springBootAutoconfigs, springAutoconfigConditions,
                dubboRegistries,
                dubboXmlFiles, mybatisXmlFiles, springXmlFiles);
    }

    /**
     * End-to-end: resolve the GAV, then index the artifact.
     */
    public IndexReport indexOneGav(String repoName, String rootPath, io.jrdi.core.coord.Gav gav) {
        FetchResult fr = fetcher.fetch(gav);
        ArtifactInput input = new ArtifactInput(gav, fr.jarPath(), fr.sourcesPath());
        return indexRepo(repoName, rootPath, List.of(input));
    }

    private ClassVisitStats processOneClass(
            Fqn owner, Path jarPath, Optional<Path> sourcesPath,
            long artifactId,
            ClassRepo classRepo, MethodRepo methodRepo, FieldRepo fieldRepo,
            InvokeRepo invokeRepo, LambdaRepo lambdaRepo, IssueRepo issueRepo,
            FileRepo fileRepo) {

        byte[] bytes = readClassBytes(jarPath, owner.slashed());
        if (bytes == null) {
            issueRepo.record("class_read", owner.slashed(), "no .class entry in jar",
                    IssueRepo.Severity.WARN, Instant.now().toString());
            return ClassVisitStats.ofEmpty(1);
        }

        // P3.6: incremental check. If the class's .class bytes hash matches the
        // previously-recorded hash, skip all per-class work and just keep the
        // existing rows in place. This is the fast path for re-indexing an
        // unchanged jar.
        String classRelPath = "classes/" + owner.slashed() + ".class";
        String newHash = sha256Hex(bytes);
        var existingFile = fileRepo.findByPath(artifactId, classRelPath);
        if (existingFile.isPresent() && newHash.equals(existingFile.get().sha256())) {
            return ClassVisitStats.markSkipped();
        }
        // Hash changed (or first time): drop the old class row so CASCADE clears
        // the dependents. The upsert below will re-create everything.
        classRepo.deleteByFqn(owner);

        PassResult r = bytecodePass.run(owner, bytes);

        long classId = classRepo.upsert(owner, r.access(), r.superFqn().orElse(null), null,
                r.classSignatureRaw(), "jar", r.interfaces());
        // Record (or update) the file hash for the next incremental pass.
        fileRepo.upsert(artifactId, classRelPath, "class", 0L, newHash);

        // Methods
        Map<MethodKey, Long> methodIds = new HashMap<>();
        for (PassResult.MethodInfo m : r.methods()) {
            long mid = methodRepo.upsert(classId, m.name(), m.desc(), m.signatureRaw(),
                    m.startLine(), m.endLine(), m.virtual());
            methodIds.put(new MethodKey(m.name(), m.desc()), mid);
        }

        // Fields
        for (PassResult.FieldInfo f : r.fields()) {
            fieldRepo.upsert(classId, f.name(), f.desc(), f.signatureRaw(), f.line());
        }

        // Lambdas (enclosing method must already be present)
        for (PassResult.LambdaInfo l : r.lambdas()) {
            PassResult.MethodInfo enc = r.methods().get(l.enclosingMethodIndex());
            long encId = methodIds.getOrDefault(new MethodKey(enc.name(), enc.desc()), -1L);
            if (encId > 0) {
                lambdaRepo.upsert(encId, null, l.bsmTarget(), l.line());
            }
        }

        // Invokes
        List<InvokeRepo.Edge> invokeEdges = new ArrayList<>();
        int reflectUncertain = 0;
        for (PassResult.InvokeEdge e : r.invokes()) {
            PassResult.MethodInfo caller = r.methods().get(e.methodIndex());
            long callerId = methodIds.getOrDefault(new MethodKey(caller.name(), caller.desc()), -1L);
            if (callerId <= 0) continue;
            invokeEdges.add(new InvokeRepo.Edge(
                    callerId,
                    e.calleeOwner(),
                    e.calleeName(),
                    e.calleeDesc(),
                    mapKind(e.kind()),
                    e.line(),
                    mapConfidence(e.confidence())));
            if (e.kind() == PassResult.Kind.REFLECT && e.confidence() == Confidence.UNCERTAIN) {
                reflectUncertain++;
            }
        }
        if (!invokeEdges.isEmpty()) invokeRepo.insertAll(invokeEdges);

        // Reflect issues
        for (int i = 0; i < reflectUncertain; i++) {
            issueRepo.record("uncertain_reflect", owner.slashed(), "non-constant reflect argument",
                    IssueRepo.Severity.WARN, Instant.now().toString());
        }

        // Source / decompile enrichment (best-effort, replaces start_line / end_line)
        enrichLineNumbers(owner, r, bytes, sourcesPath, methodRepo);

        // Framework passes (Spring / Dubbo / MyBatis) are now run in a second
        // pass after all classes in the artifact are recorded. This lets the
        // Spring DI candidate resolver see the full class set (including the
        // `interfaces` column written by the bytecode pass for every class).
        FrameworkStats framework = FrameworkStats.EMPTY;

        return new ClassVisitStats(
                1, r.methods().size(), r.fields().size(),
                r.invokes().size(), r.lambdas().size(), reflectUncertain,
                0, framework);
    }

    private void enrichLineNumbers(
            Fqn owner, PassResult r, byte[] classBytes, Optional<Path> sourcesPath,
            MethodRepo methodRepo) {

        // Load source text once and reuse for every method's MethodMatcher call.
        String sourceText = sourcesPath.isPresent()
                ? sourceLoader.read(sourcesPath.get(), owner).orElse("")
                : null;

        for (int i = 0; i < r.methods().size(); i++) {
            PassResult.MethodInfo m = r.methods().get(i);
            MethodKey k = new MethodKey(m.name(), m.desc());
            Optional<MethodMatcher.SourceFacts> oneMethodFacts;
            if (sourcesPath.isPresent()) {
                oneMethodFacts = methodMatcher.match(sourceText, owner, k);
            } else {
                oneMethodFacts = virtualLineAssigner.assign(classBytes, owner, k);
            }
            if (oneMethodFacts.isPresent() && oneMethodFacts.get().hasLines()) {
                long classId = methodRepo.findByKey(owner, k)
                        .map(MethodRepo.Record::classId)
                        .orElse(-1L);
                if (classId > 0) {
                    methodRepo.upsert(classId, m.name(), m.desc(), m.signatureRaw(),
                            oneMethodFacts.get().startLine(),
                            oneMethodFacts.get().endLine(),
                            sourcesPath.isEmpty());  // virtual=true iff CFR-only
                }
            }
        }
    }

    /**
     * Result of running the framework analyzers on a single class. Empty when the source
     * text is not available (no sources.jar and no CFR fallback for source code).
     */
    record FrameworkStats(int springBeans, int springInjects,
                          int dubboServices, int dubboReferences,
                          int mybatisStatements) {

        static final FrameworkStats EMPTY = new FrameworkStats(0, 0, 0, 0, 0);

        FrameworkStats add(FrameworkStats other) {
            return new FrameworkStats(
                    springBeans + other.springBeans,
                    springInjects + other.springInjects,
                    dubboServices + other.dubboServices,
                    dubboReferences + other.dubboReferences,
                    mybatisStatements + other.mybatisStatements);
        }
    }

    /**
     * Run Dubbo / MyBatis passes against the source text of one class. Spring has been
     * split into {@code recordBeans} + {@code resolveInjects} which the pipeline calls
     * separately (see the 2-pass framework loop in {@code indexRepo}). Each remaining
     * pass is a no-op on classes without its annotations, so the cost on non-framework
     * projects is just the AST parse + a few method-level lookups. All errors are caught
     * and downgraded to debug log + skip — framework passes must not break indexing of
     * plain Java code.
     */
    private FrameworkStats runOtherFrameworkPasses(Fqn owner, Optional<Path> sourcesPath) {
        if (sourcesPath.isEmpty()) return FrameworkStats.EMPTY;
        String sourceText = sourceLoader.read(sourcesPath.get(), owner).orElse(null);
        if (sourceText == null || sourceText.isBlank()) return FrameworkStats.EMPTY;
        int dS = 0, dR = 0, mS = 0;
        // Spring is handled separately via recordBeans + resolveInjects in
        // the pipeline's 2-phase loop. This method runs only the non-Spring
        // passes (Dubbo, MyBatis), which have no class-set dependencies and
        // can run per-class in any order.
        try {
            io.jrdi.dubbo.DubboPass.Result r = dubboPass.scan(sourceText, owner);
            dS = r.servicesRecorded();
            dR = r.referencesRecorded();
        } catch (Exception e) {
            LOG.debug("dubbo pass failed on {}: {}", owner, e.getMessage());
        }
        try {
            io.jrdi.mybatis.MybatisPass.Result r = mybatisPass.scan(sourceText, owner);
            mS = r.statementsRecorded();
        } catch (Exception e) {
            LOG.debug("mybatis pass failed on {}: {}", owner, e.getMessage());
        }
        return new FrameworkStats(0, 0, dS, dR, mS);
    }

    private byte[] readClassBytes(Path jarPath, String slashed) {
        String entry = slashed + ".class";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            java.util.jar.JarEntry e = jar.getJarEntry(entry);
            if (e == null) return null;
            try (InputStream in = jar.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static InvokeRepo.Kind mapKind(PassResult.Kind k) {
        return switch (k) {
            case VIRTUAL -> InvokeRepo.Kind.VIRTUAL;
            case STATIC -> InvokeRepo.Kind.STATIC;
            case SPECIAL -> InvokeRepo.Kind.SPECIAL;
            case INTERFACE -> InvokeRepo.Kind.INTERFACE;
            case DYNAMIC -> InvokeRepo.Kind.DYNAMIC;
            case REFLECT -> InvokeRepo.Kind.REFLECT;
        };
    }

    private static InvokeRepo.Confidence mapConfidence(Confidence c) {
        return switch (c) {
            case CERTAIN -> InvokeRepo.Confidence.CERTAIN;
            case PROBABLE -> InvokeRepo.Confidence.PROBABLE;
            case UNCERTAIN -> InvokeRepo.Confidence.UNCERTAIN;
        };
    }

    public Db db() {
        return db;
    }

    private record ClassVisitStats(int classes, int methods, int fields, int invokes,
                                   int lambdas, int issues, int skipped,
                                   FrameworkStats framework) {
        static ClassVisitStats ofEmpty(int issues) {
            return new ClassVisitStats(0, 0, 0, 0, 0, issues, 0, FrameworkStats.EMPTY);
        }
        /** Build a "skipped" stats record (incremental: hash matched the DB). */
        static ClassVisitStats markSkipped() {
            return new ClassVisitStats(0, 0, 0, 0, 0, 0, 1, FrameworkStats.EMPTY);
        }
        ClassVisitStats addFramework(FrameworkStats f) {
            return new ClassVisitStats(classes, methods, fields, invokes, lambdas, issues, skipped, framework.add(f));
        }
    }

    /**
     * Hex SHA-256 of a byte array. Used by the P3.6 incremental-indexing path to
     * decide whether a class's .class bytes are unchanged between two index runs.
     */
    static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
