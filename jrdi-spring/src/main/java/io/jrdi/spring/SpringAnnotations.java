package io.jrdi.spring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.jrdi.core.symbol.Fqn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Static helpers for recognising the small set of Spring annotations jrdi cares about.
 * <p>
 * P2 scope: component-scan, @Bean methods, @Autowired / @Resource / @Qualifier.
 * {@code @Conditional*} and friends are explicitly out of scope and produce a runtime
 * warning via {@code issues} table.
 */
public final class SpringAnnotations {

    private static final Logger LOG = LoggerFactory.getLogger(SpringAnnotations.class);

    /** Component-scan target annotations. Their bean name defaults to the lowercased simple class name. */
    public static final List<String> STEREOTYPE_ANNOTATIONS = List.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration"
    );

    /** Bean definition annotations on methods (returned type is the bean type). */
    public static final List<String> BEAN_ANNOTATIONS = List.of(
            "org.springframework.context.annotation.Bean"
    );

    /** Wiring annotations on fields, parameters, and methods. */
    public static final List<String> WIRING_ANNOTATIONS = List.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "javax.annotation.Resource",
            "jakarta.annotation.Resource"
    );

    public static final String QUALIFIER_ANNOTATION = "org.springframework.beans.factory.annotation.Qualifier";

    private SpringAnnotations() {
    }

    public static boolean isStereotype(AnnotationExpr expr) {
        return matchesAny(expr, STEREOTYPE_ANNOTATIONS);
    }

    public static boolean isBean(AnnotationExpr expr) {
        return matchesAny(expr, BEAN_ANNOTATIONS);
    }

    public static boolean isWiring(AnnotationExpr expr) {
        return matchesAny(expr, WIRING_ANNOTATIONS);
    }

    public static boolean isQualifier(AnnotationExpr expr) {
        return matchesAny(expr, List.of(QUALIFIER_ANNOTATION));
    }

    public static String simpleName(AnnotationExpr expr) {
        return expr.getNameAsString();
    }

    /**
     * Default bean name for a stereotype-annotated class: lowercased simple class name.
     * Spring uses this exact rule; we follow it.
     */
    public static String defaultBeanName(ClassOrInterfaceDeclaration cd) {
        return Character.toLowerCase(cd.getNameAsString().charAt(0))
                + cd.getNameAsString().substring(1);
    }

    /**
     * Extract a {@code value} attribute from a single- or normal-membership annotation.
     * Returns empty when the attribute is missing.
     */
    public static Optional<String> stringValue(AnnotationExpr expr, String attribute) {
        if (expr instanceof SingleMemberAnnotationExpr sm) {
            if ("value".equals(attribute) || (attribute == null)) {
                Expression inner = sm.getMemberValue();
                if (inner instanceof StringLiteralExpr sl) return Optional.of(sl.getValue());
            }
            return Optional.empty();
        }
        if (expr instanceof NormalAnnotationExpr ne) {
            for (MemberValuePair p : ne.getPairs()) {
                if (attribute.equals(p.getNameAsString())) {
                    Expression v = p.getValue();
                    if (v instanceof StringLiteralExpr sl) return Optional.of(sl.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean matchesAny(AnnotationExpr expr, List<String> candidates) {
        String n = expr.getNameAsString();
        // accept both fully-qualified and short names
        for (String c : candidates) {
            String shortC = shortName(c);
            if (n.equals(c)) return true;            // exact FQN
            if (n.equals(shortC)) return true;        // short name
            if (n.endsWith("." + shortC)) return true;  // trailing segment
        }
        return false;
    }

    private static String shortName(String fqn) {
        int slash = fqn.lastIndexOf('/');
        int dot = fqn.lastIndexOf('.');
        return fqn.substring(Math.max(slash, dot) + 1);
    }

    /**
     * Best-effort resolve of a type reference to its FQN by walking up the AST
     * and consulting the compilation unit's imports.
     *
     * <ol>
     *   <li>If the type reference already contains a dot (the user wrote
     *       {@code com.acme.api.OrderApi}), use it as-is.</li>
     *   <li>Otherwise look for a matching single-type import in the CU and
     *       use the imported FQN. This is the common case: an interface or
     *       domain class that's imported at the top of the file.</li>
     *   <li>Fall back to the current package — covers classes in the same
     *       package as the consumer (no import needed).</li>
     *   <li>Last resort: return the bare short name so the candidate resolver
     *       can still attempt a (low-confidence) match.</li>
     * </ol>
     */
    public static Fqn resolveType(CompilationUnit cu, com.github.javaparser.ast.type.Type t) {
        String name = t.toString();
        // Strip array brackets for the lookup; re-attach if needed.
        boolean isArray = name.endsWith("[]");
        String lookup = isArray ? name.substring(0, name.length() - 2) : name;
        if (lookup.contains(".")) {
            return Fqn.fromDotted(name);
        }
        // 2. Look for a matching single-type import
        for (var imp : cu.getImports()) {
            if (imp.isAsterisk()) continue;
            String impName = imp.getNameAsString();
            if (impName == null) continue;
            int lastDot = impName.lastIndexOf('.');
            if (lastDot < 0) continue;
            String simple = impName.substring(lastDot + 1);
            if (simple.equals(lookup)) {
                return Fqn.fromDotted(impName);
            }
        }
        // 3. Fall back to current package
        if (cu.getPackageDeclaration().isPresent()) {
            String pkg = cu.getPackageDeclaration().get().getNameAsString();
            return Fqn.fromDotted(pkg + "." + name);
        }
        return Fqn.fromDotted(name);
    }
}
