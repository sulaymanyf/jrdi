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
 */package io.jrdi.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.source.AstBuilder;
import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.FieldRepo;
import io.jrdi.storage.repo.MethodRepo;
import io.jrdi.storage.repo.SpringBeanRepo;
import io.jrdi.storage.repo.SpringInjectRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring analyzer: scans a single .java source file and records:
 * <ul>
 *   <li>Bean declarations (component-scan, @Bean methods) → {@code spring_beans}</li>
 *   <li>Wiring sites (@Autowired / @Resource on fields, parameters, methods) →
 *       {@code spring_injects} with candidate-bean resolution</li>
 * </ul>
 *
 * <p>Candidate resolution strategy (P2.5 — interface-aware):
 * <ol>
 *   <li>By qualifier — confidence CERTAIN (if exactly one bean with the qualifier name)</li>
 *   <li>By name — confidence CERTAIN (if exactly one bean with the field's name)</li>
 *   <li>By type, exact {@code bean.typeFqn == fieldType} — CERTAIN if 1 match, PROBABLE if &gt;1</li>
 *   <li>By type, {@code bean.typeFqn} implements / extends {@code fieldType}
 *       (interface or abstract class inject) — PROBABLE</li>
 *   <li>No match — confidence UNCERTAIN, candidate list empty</li>
 * </ol>
 */
public final class SpringPass {

    private static final Logger LOG = LoggerFactory.getLogger(SpringPass.class);

    private final Db db;
    private final AstBuilder ast;

    public SpringPass(Db db) {
        this(db, new AstBuilder());
    }

    public SpringPass(Db db, AstBuilder ast) {
        this.db = db;
        this.ast = ast;
    }

    /**
     * Scan a single source file and persist Spring facts.
     *
     * @return a {@link Result} summary
     */
    /**
     * Phase 1: collect every Spring bean declared in {@code source} (stereotype
     * annotations + @Bean methods on @Configuration classes) and write them to
     * {@code spring_beans}. Returns the number of beans recorded. Callers
     * that want candidate resolution to see the full bean set should batch
     * calls to {@code recordBeans} across the whole artifact before invoking
     * {@code resolveInjects}.
     */
    public int recordBeans(String source, Fqn owner) {
        Optional<CompilationUnit> cuOpt = ast.parse(source);
        if (cuOpt.isEmpty()) return 0;
        CompilationUnit cu = cuOpt.get();
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        MethodRepo methodRepo = SqliteRepos.methodRepo(db);
        SpringBeanRepo beanRepo = SqliteRepos.springBeanRepo(db);

        int beans = 0;
        Long classId = classRepo.findByFqn(owner).map(ClassRepo.Record::id).orElse(null);

        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration cd)) continue;
            for (AnnotationExpr ann : cd.getAnnotations()) {
                if (SpringAnnotations.isStereotype(ann)) {
                    String name = SpringAnnotations.stringValue(ann, "value")
                            .orElseGet(() -> SpringAnnotations.defaultBeanName(cd));
                    String scope = SpringAnnotations.stringValue(ann, "scope").orElse("singleton");
                    boolean primary = false;
                    Long cid = classId;
                    beanRepo.upsert(name, owner, "annotation", cid, null, scope, primary);
                    beans++;
                }
            }
            boolean isConfig = cd.getAnnotations().stream()
                    .anyMatch(a -> SpringAnnotations.isStereotype(a)
                            && a.getNameAsString().contains("Configuration"));
            if (isConfig) {
                for (MethodDeclaration md : cd.getMethods()) {
                    for (AnnotationExpr ann : md.getAnnotations()) {
                        if (SpringAnnotations.isBean(ann)) {
                            String beanName = SpringAnnotations.stringValue(ann, "value")
                                    .orElseGet(md::getNameAsString);
                            Fqn returnType = Fqn.fromDotted(md.getType().toString());
                            Long methodId = methodRepo.findByKey(owner,
                                    new io.jrdi.core.symbol.MethodKey(
                                            md.getNameAsString(),
                                            descriptorFromParams(md)))
                                    .map(MethodRepo.Record::id).orElse(null);
                            beanRepo.upsert(beanName, returnType, "config", classId, methodId,
                                    "singleton", false);
                            beans++;
                        }
                    }
                }
            }
        }
        return beans;
    }

    /**
     * Phase 2: scan the @Autowired sites in {@code source} and record each one
     * with its candidate beans. By the time this is called, every Spring bean
     * for the artifact should already be in the DB (see {@link #recordBeans}).
     * Returns the number of inject sites recorded.
     */
    public int resolveInjects(String source, Fqn owner) {
        Optional<CompilationUnit> cuOpt = ast.parse(source);
        if (cuOpt.isEmpty()) return 0;
        CompilationUnit cu = cuOpt.get();
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        FieldRepo fieldRepo = SqliteRepos.fieldRepo(db);
        SpringBeanRepo beanRepo = SqliteRepos.springBeanRepo(db);
        SpringInjectRepo injectRepo = SqliteRepos.springInjectRepo(db);

        int injects = 0;
        Long classId = classRepo.findByFqn(owner).map(ClassRepo.Record::id).orElse(null);

        // All beans in the DB at this point — used for cross-class candidate resolution.
        List<SpringBeanRepo.Record> allBeans = allBeansInDb(beanRepo);

        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration cd)) continue;
            for (var field : cd.getFields()) {
                for (var ann : field.getAnnotations()) {
                    if (SpringAnnotations.isWiring(ann)) {
                        for (var vd : field.getVariables()) {
                            Fqn fieldType = SpringAnnotations.resolveType(cu,
                                    field.getElementType());
                            Long fieldId = null;
                            if (classId != null) {
                                fieldId = fieldRepo.findByKey(owner, new io.jrdi.core.symbol.MethodKey(
                                                vd.getNameAsString(),
                                                descriptorFromType(field.getElementType().toString())))
                                        .map(FieldRepo.Record::id).orElse(null);
                            }
                            String qualifier = field.getAnnotations().stream()
                                    .filter(SpringAnnotations::isQualifier)
                                    .findFirst()
                                    .flatMap(a -> SpringAnnotations.stringValue(a, "value"))
                                    .orElse(null);
                            String byName = field.getAnnotations().stream()
                                    .filter(SpringAnnotations::isQualifier)
                                    .findFirst()
                                    .flatMap(a -> SpringAnnotations.stringValue(a, "value"))
                                    .orElse(null);
                            InjectionResult ir = resolve(fieldType, byName, qualifier, allBeans);
                            injectRepo.record(
                                    vd.getNameAsString(),
                                    null,
                                    classId != null ? classId : -1L,
                                    fieldId,
                                    qualifier,
                                    ir.by,
                                    ir.confidence,
                                    ir.candidateIds);
                            injects++;
                        }
                    }
                }
            }
            for (var md : cd.getMethods()) {
                for (int i = 0; i < md.getParameters().size(); i++) {
                    Parameter p = md.getParameter(i);
                    for (var ann : p.getAnnotations()) {
                        if (SpringAnnotations.isWiring(ann)) {
                            Fqn paramType = SpringAnnotations.resolveType(cu, p.getType());
                            String qualifier = p.getAnnotations().stream()
                                    .filter(SpringAnnotations::isQualifier)
                                    .findFirst()
                                    .flatMap(a -> SpringAnnotations.stringValue(a, "value"))
                                    .orElse(null);
                            InjectionResult ir = resolve(paramType, null, qualifier, allBeans);
                            injectRepo.record(
                                    null,
                                    i,
                                    classId != null ? classId : -1L,
                                    null,
                                    qualifier,
                                    ir.by,
                                    ir.confidence,
                                    ir.candidateIds);
                            injects++;
                        }
                    }
                }
            }
        }
        return injects;
    }

    /**
     * Convenience: do both phases in a single call. Useful for tests and
     * ad-hoc single-class scans; the pipeline should call the two phases
     * separately so all beans are visible during inject resolution.
     */
    public Result scan(String source, Fqn owner) {
        int beans = recordBeans(source, owner);
        int injects = resolveInjects(source, owner);
        return new Result(beans, injects);
    }

    private static List<SpringBeanRepo.Record> allBeansInDb(SpringBeanRepo repo) {
        // P2.5: real "list all" backed by a SQL query. Capped at 10,000 rows by the repo.
        return repo.findAll();
    }

    private InjectionResult resolve(Fqn type, String byName, String qualifier,
                                    List<SpringBeanRepo.Record> allBeans) {
        // 1. By qualifier (highest priority)
        if (qualifier != null) {
            for (var b : allBeans) {
                if (b.name().equals(qualifier)) {
                    return new InjectionResult(SpringInjectRepo.By.QUALIFIER,
                            SpringInjectRepo.Confidence.CERTAIN, List.of(b.id()));
                }
            }
            return new InjectionResult(SpringInjectRepo.By.QUALIFIER,
                    SpringInjectRepo.Confidence.UNCERTAIN, List.of());
        }
        // 2. By name (if the field is named like a bean)
        if (byName != null) {
            for (var b : allBeans) {
                if (b.name().equals(byName)) {
                    return new InjectionResult(SpringInjectRepo.By.NAME,
                            SpringInjectRepo.Confidence.CERTAIN, List.of(b.id()));
                }
            }
        }
        // 3. By type (exact match on the bean's typeFqn)
        if (allBeans != null && !allBeans.isEmpty()) {
            List<Long> hits = new ArrayList<>();
            for (var b : allBeans) {
                if (b.typeFqn().equals(type.slashed())) {
                    hits.add(b.id());
                }
            }
            if (!hits.isEmpty()) {
                return new InjectionResult(SpringInjectRepo.By.TYPE,
                        hits.size() == 1 ? SpringInjectRepo.Confidence.CERTAIN
                                : SpringInjectRepo.Confidence.PROBABLE, hits);
            }
        }
        // 4. By implemented interface / supertype. The field type might be an
        //    interface or abstract class; the bean is registered with the impl
        //    class. Use ClassRepo.findSubtypesOf to find all indexed classes
        //    that implement / extend `type` and intersect with the bean set.
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        List<Fqn> subtypes = classRepo.findSubtypesOf(type);
        if (!subtypes.isEmpty() && allBeans != null && !allBeans.isEmpty()) {
            // Build a FQN set for O(1) membership.
            java.util.Set<String> subtypeSet = new java.util.HashSet<>();
            for (Fqn s : subtypes) subtypeSet.add(s.slashed());
            List<Long> hits = new ArrayList<>();
            for (var b : allBeans) {
                if (subtypeSet.contains(b.typeFqn())) {
                    hits.add(b.id());
                }
            }
            if (!hits.isEmpty()) {
                // Interface-based match is best-effort: confidence is always PROBABLE
                // (no further narrowing on the candidates), even with a single hit.
                return new InjectionResult(SpringInjectRepo.By.TYPE,
                        SpringInjectRepo.Confidence.PROBABLE, hits);
            }
        }
        return new InjectionResult(SpringInjectRepo.By.TYPE,
                SpringInjectRepo.Confidence.UNCERTAIN, List.of());
    }

    private static String descriptorFromType(String type) {
        return "L" + type.replace('.', '/') + ";";
    }

    private static String descriptorFromParams(MethodDeclaration md) {
        StringBuilder sb = new StringBuilder("(");
        for (Parameter p : md.getParameters()) {
            String t = p.getType().toString();
            if (t.endsWith("[]")) {
                sb.append("[").append(t.substring(0, t.length() - 2));
            } else {
                sb.append("L").append(t.replace('.', '/')).append(";");
            }
        }
        sb.append(")");
        String ret = md.getType().toString();
        if (ret.equals("void")) sb.append("V");
        else sb.append("L").append(ret.replace('.', '/')).append(";");
        return sb.toString();
    }

    private record InjectionResult(SpringInjectRepo.By by,
                                   SpringInjectRepo.Confidence confidence,
                                   List<Long> candidateIds) {}

    public record Result(int beansRecorded, int injectsRecorded) {}
}
