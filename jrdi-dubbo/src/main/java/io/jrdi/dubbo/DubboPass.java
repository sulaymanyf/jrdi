package io.jrdi.dubbo;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.source.AstBuilder;
import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
import io.jrdi.storage.repo.FieldRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dubbo analyzer: recognises provider (@DubboService, @Service) and consumer
 * (@DubboReference, @Reference) annotations in a single .java source file.
 *
 * <p>XML schema parsing of {@code <dubbo:service interface="..." ref="..." />} and
 * {@code <dubbo:reference id="..." interface="..." />} is NOT in M1 — these come
 * from {@code dubbo-provider.xml} / {@code dubbo-consumer.xml} under
 * {@code META-INF/spring/} or {@code classpath:}. Tracking issue: post-M1 (0.2.0+).
 *
 * <p>M1 limitations:
 * <ul>
 *   <li>Annotation-only. No XML schema, no {@code spring.factories} / auto-config, no
 *       {@code application.yml} {@code dubbo.consumer.*} introspection.
 *   <li>No interface-vs-impl matching beyond sharing the same FQN — runtime proxy
 *       resolution is out of scope (see docs/architecture.md "Static-only" note).
 *   <li>No multi-protocol routing beyond a single {@code protocol} attribute.
 *   <li>Provider-class lookup is by file-level class declaration; nested classes are not
 *       currently walked.
 *   <li>No method-level annotations ({@code @DubboMethod} timeout / retries / LB).
 *   <li>Generic / generic-call consumers ({@code GenericService.$invoke}) are recorded
 *       as {@code UNCERTAIN} in {@code invokes} but not in {@code dubbo_references}.
 * </ul>
 */
public final class DubboPass {

    private static final Logger LOG = LoggerFactory.getLogger(DubboPass.class);

    public static final List<String> SERVICE_ANNOTATIONS = List.of(
            "org.apache.dubbo.config.annotation.DubboService",
            "com.alibaba.dubbo.config.annotation.Service"  // legacy Apache Dubbo 2.6.x
    );

    public static final List<String> REFERENCE_ANNOTATIONS = List.of(
            "org.apache.dubbo.config.annotation.DubboReference",
            "com.alibaba.dubbo.config.annotation.Reference"
    );

    private final Db db;
    private final AstBuilder ast;

    public DubboPass(Db db) {
        this(db, new AstBuilder());
    }

    public DubboPass(Db db, AstBuilder ast) {
        this.db = db;
        this.ast = ast;
    }

    public Result scan(String source, Fqn owner) {
        Optional<CompilationUnit> cuOpt = ast.parse(source);
        if (cuOpt.isEmpty()) return new Result(0, 0);
        CompilationUnit cu = cuOpt.get();

        ClassRepo classRepo = SqliteRepos.classRepo(db);
        FieldRepo fieldRepo = SqliteRepos.fieldRepo(db);
        DubboServiceRepo serviceRepo = SqliteRepos.dubboServiceRepo(db);
        DubboReferenceRepo referenceRepo = SqliteRepos.dubboReferenceRepo(db);

        Long classId = classRepo.findByFqn(owner).map(ClassRepo.Record::id).orElse(null);

        int services = 0;
        int references = 0;

        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration cd)) continue;
            // @DubboService on a class
            for (AnnotationExpr ann : cd.getAnnotations()) {
                if (matchesAny(ann, SERVICE_ANNOTATIONS)) {
                    Fqn iface = implementedInterface(cd);
                    if (iface == null) {
                        LOG.debug("@DubboService on {} but no interface implemented", owner);
                        continue;
                    }
                    String group = stringValue(ann, "group").orElse("");
                    String version = stringValue(ann, "version").orElse("");
                    String protocol = stringValue(ann, "protocol").orElse("dubbo");
                    // Record the service even when the impl class isn't indexed yet.
                    // When classId is null (e.g., source-only analysis), we use 0 as a
                    // sentinel; the pipeline's bytecode pass will rewrite this row.
                    long implId = classId != null ? classId : 0L;
                    serviceRepo.upsert(iface, implId, group, version, protocol, "annotation", "", "");
                    services++;
                }
            }
            // @DubboReference on fields
            for (var field : cd.getFields()) {
                for (AnnotationExpr ann : field.getAnnotations()) {
                    if (matchesAny(ann, REFERENCE_ANNOTATIONS)) {
                        Fqn iface = interfaceFromType(field.getElementType());
                        if (iface == null) continue;
                        // Find the field id in the storage. We use the actual
                        // variable name (not a String-split of toString()), and
                        // the field descriptor for the type.
                        String group = stringValue(ann, "group").orElse("");
                        String version = stringValue(ann, "version").orElse("");
                        String fieldName = field.getVariables().isEmpty()
                                ? null
                                : field.getVariables().get(0).getNameAsString();
                        if (fieldName == null) continue;
                        String desc = descriptorFromType(field.getElementType().toString());
                        var fieldRecord = fieldRepo.findByKey(owner,
                                new io.jrdi.core.symbol.MethodKey(fieldName, desc));
                        if (fieldRecord.isPresent()) {
                            referenceRepo.record(iface, fieldRecord.get().id(), group, version,
                                    DubboReferenceRepo.Confidence.PROBABLE, "", "",
                                    owner.slashed());
                            references++;
                        } else {
                            // Cross-jar: the field is in a dependency that we
                            // don't have the bytecode for. Record the consumer
                            // class FQN so the LLM can still find the call
                            // site via the consumer FQN, and use field_id = 0
                            // as a sentinel (the V1 schema requires
                            // field_id NOT NULL, but the impl is allowed to
                            // use 0 as a cross-jar placeholder).
                            referenceRepo.record(iface, 0L, group, version,
                                    DubboReferenceRepo.Confidence.UNCERTAIN, "", "",
                                    owner.slashed());
                            references++;
                        }
                    }
                }
            }
        }

        return new Result(services, references);
    }

    private static Fqn implementedInterface(ClassOrInterfaceDeclaration cd) {
        // First implemented interface (Dubbo allows only one per service impl).
        if (cd.getImplementedTypes().isEmpty()) return null;
        return Fqn.fromDotted(cd.getImplementedTypes(0).toString());
    }

    private static Fqn interfaceFromType(com.github.javaparser.ast.type.Type t) {
        if (t instanceof ClassOrInterfaceType cit) {
            return Fqn.fromDotted(cit.toString());
        }
        return Fqn.fromDotted(t.toString());
    }

    private static String descriptorFromType(String type) {
        return "L" + type.replace('.', '/') + ";";
    }

    private static boolean matchesAny(AnnotationExpr expr, List<String> candidates) {
        String n = expr.getNameAsString();
        for (String c : candidates) {
            // FQN match: always accepted.
            if (n.equals(c)) return true;
            // FQN-with-trailing-segment: "@foo.bar.X" matches "x.y.z.X".
            String shortC = shortName(c);
            if (n.endsWith("." + shortC)) return true;
            // Short-name match: only for FQNs whose short name is NOT a generic word
            // that would collide with annotations from other frameworks (e.g., Spring's
            // "Service" vs Alibaba Dubbo's "Service"). See KNOWN_AMBIGUOUS_SHORT_NAMES.
            if (n.equals(shortC) && !KNOWN_AMBIGUOUS_SHORT_NAMES.contains(shortC)) return true;
        }
        return false;
    }

    /**
     * Short annotation names that are too generic to disambiguate by short name alone.
     * For these, callers must use the FQN to opt in to legacy Alibaba Dubbo 2.6.x.
     * The Spring {@code @Service} annotation shares the same short name, so without
     * this guard, a class annotated with {@code @Service} (Spring) would be mistakenly
     * classified as a Dubbo service.
     */
    private static final java.util.Set<String> KNOWN_AMBIGUOUS_SHORT_NAMES =
            java.util.Set.of("Service", "Reference");

    private static String shortName(String fqn) {
        int slash = fqn.lastIndexOf('/');
        int dot = fqn.lastIndexOf('.');
        return fqn.substring(Math.max(slash, dot) + 1);
    }

    private static Optional<String> stringValue(AnnotationExpr expr, String attribute) {
        if (expr instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr sm) {
            if ("value".equals(attribute)) {
                Expression inner = sm.getMemberValue();
                if (inner instanceof com.github.javaparser.ast.expr.StringLiteralExpr sl) {
                    return Optional.of(sl.getValue());
                }
            }
            return Optional.empty();
        }
        if (expr instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr ne) {
            for (var p : ne.getPairs()) {
                if (attribute.equals(p.getNameAsString())) {
                    Expression v = p.getValue();
                    if (v instanceof com.github.javaparser.ast.expr.StringLiteralExpr sl) {
                        return Optional.of(sl.getValue());
                    }
                }
            }
        }
        return Optional.empty();
    }

    public record Result(int servicesRecorded, int referencesRecorded) {}
}
