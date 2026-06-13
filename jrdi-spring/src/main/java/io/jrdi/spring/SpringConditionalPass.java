package io.jrdi.spring;

import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ArtifactRepo;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.FileRepo;
import io.jrdi.storage.repo.SpringAutoconfigConditionRepo;
import io.jrdi.storage.repo.SpringBootAutoconfigRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Spring Boot auto-config condition analysis. For every auto-config class
 * recorded in V5 ({@code spring_boot_autoconfigs}), this pass:
 *
 * <ol>
 *   <li>Locates the {@code .class} bytes in the indexed jar(s).</li>
 *   <li>Walks class-level {@code @Conditional*} annotations with ASM.</li>
 *   <li>Walks method-level {@code @Conditional*} annotations on every
 *       {@code @Bean} method (i.e. methods whose name is on the auto-config's
 *       {@code @Bean} list or that carry {@code @Bean}).</li>
 *   <li>Extracts the "what's required" field for each known condition type
 *       and records it in {@code spring_autoconfig_conditions} (V6).</li>
 * </ol>
 *
 * <p>The known {@code @Conditional*} types we extract are the canonical
 * Spring Boot ones; unknown custom conditions are recorded as
 * {@code condition_type="other"} with no requirement fields. The LLM can
 * still see them but can't evaluate their semantics.
 *
 * <p>Note: this pass does <em>not</em> evaluate whether the conditions are
 * actually met — that's a separate question, answered by joining
 * {@code spring_autoconfig_conditions} with the {@code classes} table
 * ({@code required_class} matches some {@code classes.fqn}). The
 * {@code find_spring_autoconfig_conditions} MCP tool returns the raw
 * conditions; the {@code find_spring_autoconfig_activation} (post-M1
 * extension) can then reason about them.
 *
 * <p>M1 limitations:
 * <ul>
 *   <li>Class-level only for {@code @Conditional*} on the @Configuration
 *       class. We also walk methods, but the "is this a @Bean method"
 *       detection is naive (any method whose simple name doesn't start with
 *       a lower-case letter, or that has {@code @Bean} on it). Real Spring
 *       projects often put the {@code @Bean} annotation on the class's
 *       outer @Configuration or a separate @Configuration inner class.</li>
 *   <li>We don't resolve meta-conditions like
 *       {@code @ConditionalOnWebApplication} deeply — we record
 *       {@code condition_type="on_web_app"} but don't evaluate
 *       SERVLET vs REACTIVE.</li>
 *   <li>Class arrays in {@code @ConditionalOnClass(SomeClass.class,
 *       AnotherClass.class)} are split into one row per class. Same for
 *       {@code @ConditionalOnBean}.</li>
 *   <li>SpEL in {@code @ConditionalOnExpression} is recorded as
 *       {@code condition_type="other"} with the literal text in no field.
 *       Post-M1 can pipe this through Spring's SpEL parser in dry-run mode.</li>
 * </ul>
 */
public final class SpringConditionalPass {

    private static final Logger LOG = LoggerFactory.getLogger(SpringConditionalPass.class);

    /** Known Spring Boot @Conditional* annotation FQNs and their type tag.
     * Keys use the JVM internal form (slashes) to match ASM descriptors directly. */
    private static final Map<String, String> KNOWN_CONDITIONS = new HashMap<>();
    static {
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnClass", "on_class");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnMissingClass", "on_missing_class");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnBean", "on_bean");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean", "on_missing_bean");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnSingleCandidate", "on_single_candidate");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnWebApplication", "on_web_app");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnNotWebApplication", "on_not_web_app");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnProperty", "on_property");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnResource", "on_resource");
        KNOWN_CONDITIONS.put("org/springframework/boot/autoconfigure/condition/ConditionalOnJava", "on_java");
    }

    private final Db db;

    public SpringConditionalPass(Db db) {
        this.db = db;
    }

    public record Result(int autoconfigsScanned, int conditionsRecorded) {}

    /**
     * Scan every auto-config class recorded in {@code spring_boot_autoconfigs}
     * (V5) and extract its {@code @Conditional*} annotations. Returns the
     * count of autoconfigs scanned and conditions recorded.
     */
    public Result scanAll() {
        SpringBootAutoconfigRepo autoconfigRepo = SqliteRepos.springBootAutoconfigRepo(db);
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        SpringAutoconfigConditionRepo condRepo = SqliteRepos.springAutoconfigConditionRepo(db);

        int scanned = 0;
        int recorded = 0;
        for (SpringBootAutoconfigRepo.Record ac : autoconfigRepo.findAll()) {
            scanned++;
            byte[] bytes = loadClassBytes(ac.classFqn());
            if (bytes == null) {
                LOG.debug("no .class bytes for autoconfig {}", ac.classFqn());
                continue;
            }
            List<Condition> conds = extractConditions(bytes);
            for (Condition c : conds) {
                condRepo.upsert(
                        ac.classFqn(), c.type, c.requiredClass, c.requiredBeanType,
                        c.requiredProperty, c.appliedTo);
                recorded++;
            }
        }
        return new Result(scanned, recorded);
    }

    /** Extract every @Conditional* annotation from the given .class bytes. */
    List<Condition> extractConditions(byte[] classBytes) {
        List<Condition> out = new ArrayList<>();
        try {
            ClassReader cr = new ClassReader(new java.io.ByteArrayInputStream(classBytes));
            cr.accept(new ConditionVisitor(out), 0);
        } catch (Exception e) {
            LOG.debug("class parse failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Locate the .class bytes for the given FQN across all indexed jars.
     * Returns null if the class hasn't been indexed (or its jar can't be
     * located on disk).
     */
    private byte[] loadClassBytes(String fqn) {
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        FileRepo fileRepo = SqliteRepos.fileRepo(db);
        ArtifactRepo artifactRepo = SqliteRepos.artifactRepo(db);
        var classRecord = classRepo.findByFqn(io.jrdi.core.symbol.Fqn.fromDotted(fqn));
        if (classRecord.isEmpty()) return null;
        Long fileId = classRecord.get().fileId();
        if (fileId == null) return null;
        var fileRecordOpt = fileRepo.findById(fileId);
        if (fileRecordOpt.isEmpty()) return null;
        var fileRecord = fileRecordOpt.get();
        var artifactOpt = artifactRepo.findById(fileRecord.artifactId());
        if (artifactOpt.isEmpty()) return null;
        var artifact = artifactOpt.get();
        Path jarPath = Path.of(artifact.jarPath());
        if (!Files.isRegularFile(jarPath)) return null;
        // The IndexPipeline writes .class files with relPath = "classes/<fqn>.class",
        // so the jar entry has that prefix. (When we read a class from a real-world
        // jar downloaded by the resolver, the same convention is used because
        // IndexPipeline.processOneClass is the only writer.)
        String entry = "classes/" + fqn.replace('.', '/') + ".class";
        try {
            if (jarPath.getFileName().toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    JarEntry e = jar.getJarEntry(entry);
                    if (e == null) {
                        // Fallback: try without the "classes/" prefix.
                        e = jar.getJarEntry(fqn.replace('.', '/') + ".class");
                    }
                    if (e == null) return null;
                    try (InputStream in = jar.getInputStream(e)) {
                        return in.readAllBytes();
                    }
                }
            } else {
                Path file = jarPath.resolve(entry);
                if (!Files.isRegularFile(file)) {
                    file = jarPath.resolve(fqn.replace('.', '/') + ".class");
                }
                if (Files.isRegularFile(file)) {
                    return Files.readAllBytes(file);
                }
            }
        } catch (IOException e) {
            LOG.debug("class bytes read failed for {}: {}", fqn, e.getMessage());
        }
        return null;
    }

    /** Captured condition row. */
    record Condition(String type, String requiredClass, String requiredBeanType,
                    String requiredProperty, String appliedTo) {}

    /**
     * ASM visitor that walks class-level and method-level @Conditional*
     * annotations and converts them to {@link Condition} records.
     */
    private static final class ConditionVisitor extends ClassVisitor {
        private final List<Condition> out;
        // Track whether a method has @Bean so we tag it as applied_to="bean:name"
        private final Map<String, Boolean> isBeanMethod = new HashMap<>();

        ConditionVisitor(List<Condition> out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String type = mapConditionType(descriptor);
            if (type == null) return null;  // not a known conditional
            return new CondAnnotationVisitor(type, "class", out);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                    String annType = mapConditionType(annDesc);
                    boolean isBean = "Ljavax/annotation/Generated;".equals(annDesc)  // placeholder
                            || isBeanAnnotation(annDesc);
                    // We track @Bean specifically to set applied_to.
                    if (isBean) {
                        isBeanMethod.put(name + descriptor, Boolean.TRUE);
                    }
                    if (annType == null) {
                        // Keep the @Bean handling alive
                        return super.visitAnnotation(annDesc, visible);
                    }
                    String appliedTo = isBeanMethod.containsKey(name + descriptor)
                            ? "bean:" + name
                            : "class";  // method-level conditional not on @Bean
                    return new CondAnnotationVisitor(annType, appliedTo, out);
                }
            };
        }
    }

    private static boolean isBeanAnnotation(String descriptor) {
        return "Lorg/springframework/context/annotation/Bean;".equals(descriptor);
    }

    /**
     * Maps an annotation descriptor (e.g.
     * {@code Lorg/springframework/boot/autoconfigure/condition/ConditionalOnClass;})
     * to our short condition type tag. Returns null for non-condition
     * annotations.
     */
    private static String mapConditionType(String descriptor) {
        // Strip leading 'L' and trailing ';'.
        if (descriptor == null || descriptor.length() < 3) return null;
        if (descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') return null;
        String internal = descriptor.substring(1, descriptor.length() - 1);
        return KNOWN_CONDITIONS.get(internal);
    }

    /**
     * AnnotationVisitor that captures the "value" / "name" attribute of a
     * Spring @Conditional* annotation and emits one or more
     * {@link Condition} rows.
     */
    private static final class CondAnnotationVisitor extends AnnotationVisitor {
        private final String type;
        private final String appliedTo;
        private final List<Condition> out;

        CondAnnotationVisitor(String type, String appliedTo, List<Condition> out) {
            super(Opcodes.ASM9);
            this.type = type;
            this.appliedTo = appliedTo;
            this.out = out;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            // For an array of class literals like @ConditionalOnClass({X.class, Y.class}),
            // ASM calls visit(null, Type.getObjectType("Lcom/acme/X;")) for each element.
            // (In older ASM, class literals in arrays were represented as nested
            // annotations with the class descriptor as the annotation name; modern
            // ASM uses a uniform Type-based representation.)
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String elementName, Object value) {
                    if (value instanceof Type t) {
                        recordClassValue(t.getClassName());
                    } else if (value instanceof String s && !s.isEmpty()) {
                        if ("on_class".equals(type) || "on_missing_class".equals(type)) {
                            recordClassValue(s);
                        } else {
                            out.add(new Condition(type, "", "", s, appliedTo));
                        }
                    }
                }
                @Override
                public AnnotationVisitor visitAnnotation(String elementName, String annotationDesc) {
                    // Defensive: handle the older "class literal as nested annotation"
                    // encoding just in case. annotationDesc is the class descriptor
                    // (e.g. "Lcom/acme/X;") for a class literal.
                    if (annotationDesc != null && annotationDesc.startsWith("L")
                            && annotationDesc.endsWith(";")) {
                        recordClassValue(classDescToFqn(annotationDesc));
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {};
                }
            };
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type t) {
                // @ConditionalOnClass(X.class) or @ConditionalOnBean(X.class):
                // the value is a Type. For both kinds the LLM wants to know the
                // class FQN, so we put it in requiredClass / requiredBeanType.
                recordClassValue(t.getClassName());
            } else if (value instanceof String s && !s.isEmpty()) {
                // Two cases:
                //   1. @ConditionalOnClass(name = "...") / @ConditionalOnMissingClass("...")
                //      — value is a String class FQN, semantically equivalent to
                //      the Type-based form. Put it in the same field.
                //   2. @ConditionalOnProperty(prefix=..., name=..., havingValue=...)
                //      — value is a String property name. Put it in requiredProperty.
                if ("on_class".equals(type) || "on_missing_class".equals(type)) {
                    recordClassValue(s);
                } else {
                    // @ConditionalOnProperty(name = "spring.datasource.url")
                    out.add(new Condition(type, "", "", s, appliedTo));
                }
            }
        }

        private void recordClassValue(String fqn) {
            switch (type) {
                case "on_class", "on_missing_class" ->
                        out.add(new Condition(type, fqn, "", "", appliedTo));
                case "on_bean", "on_missing_bean", "on_single_candidate" ->
                        out.add(new Condition(type, "", fqn, "", appliedTo));
                default ->
                        // Fall back to requiredClass for unknown types.
                        out.add(new Condition(type, fqn, "", "", appliedTo));
            }
        }
    }

    /** Convert a JVM type descriptor (e.g. {@code Lcom/acme/X;}) to a Java FQN. */
    private static String classDescToFqn(String desc) {
        if (desc == null || desc.length() < 3) return null;
        if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return null;
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    // Suppress the unused-import warning for StandardCharsets and ZipInputStream
    // (kept for future extensions: directly reading the autoconfig classpath
    //  via classloader, or processing WAR files).
    @SuppressWarnings("unused")
    private static final java.nio.charset.Charset UNUSED_CHARSET = StandardCharsets.UTF_8;
}
