package io.jrdi.source;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.core.util.Descriptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a JavaParser {@link CompilationUnit} for a given class. Parses with the
 * JavaParser facade. The {@link MethodMatcher} consumes the produced CU.
 *
 * <p>Symbol resolution is intentionally minimal in U4 — we only resolve class names in
 * the same compilation unit. Cross-jar resolution is a P3 concern (would need
 * {@code JarTypeSolver} on every dependency jar).
 */
public final class AstBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(AstBuilder.class);

    public Optional<CompilationUnit> parse(String source) {
        if (source == null || source.isEmpty()) return Optional.empty();
        try {
            // Configure the parser for Java 17 so records, sealed types, pattern matching
            // and switch expressions all parse cleanly. The compiler might still emit
            // bytecode for an older target but the SOURCE level we're parsing is 17+.
            com.github.javaparser.StaticJavaParser.getParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
            return Optional.of(com.github.javaparser.StaticJavaParser.parse(source));
        } catch (Exception e) {
            LOG.warn("parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find a top-level or nested class declaration matching {@code ownerFqn}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<TypeDeclaration<?>> findType(CompilationUnit cu, Fqn ownerFqn) {
        if (cu == null) return Optional.empty();
        String simpleName = ownerFqn.binaryName();
        return cu.findFirst((Class) TypeDeclaration.class, (java.util.function.Predicate<TypeDeclaration>) t -> {
            if (!(t instanceof ClassOrInterfaceDeclaration cd)) return false;
            if (!cd.getNameAsString().equals(simpleName)) return false;
            String fqn = resolveDottedFqn(cu, cd);
            return fqn.equals(ownerFqn.dotted());
        });
    }

    private String resolveDottedFqn(CompilationUnit cu, ClassOrInterfaceDeclaration cd) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(0, cd.getNameAsString());
        var p = cd.getParentNode();
        while (p.isPresent() && p.get() instanceof TypeDeclaration<?> outer) {
            names.add(0, outer.getNameAsString());
            p = outer.getParentNode();
        }
        String pkg = cu.getPackageDeclaration().map(x -> x.getNameAsString()).orElse("");
        return pkg.isEmpty() ? String.join(".", names) : pkg + "." + String.join(".", names);
    }

    /**
     * Find a method declaration matching {@code key} on {@code owner}. Match strategy:
     * name + parameter type-erasure count. For parameter types, we resolve simple class
     * names against the same compilation unit; if resolution fails, we fall back to the
     * erased type's simple name.
     */
    public Optional<MethodDeclaration> findMethod(TypeDeclaration<?> owner, MethodKey key) {
        if (owner == null) return Optional.empty();
        String[] paramDescs = Descriptors.parameterTypes(key.descriptor());
        for (MethodDeclaration md : owner.getMethodsByName(key.name())) {
            if (md.getParameters().size() != paramDescs.length) continue;
            if (paramTypesMatch(md.getParameters(), paramDescs, owner)) {
                return Optional.of(md);
            }
        }
        return Optional.empty();
    }

    private boolean paramTypesMatch(List<Parameter> params, String[] descs, TypeDeclaration<?> owner) {
        for (int i = 0; i < params.size(); i++) {
            Type t = params.get(i).getType();
            String erased = erasedSimpleName(t, owner);
            String expected = erasedOfDesc(descs[i]);
            if (erased == null) return false;
            if (!erased.equals(expected)) return false;
        }
        return true;
    }

    private String erasedSimpleName(Type t, TypeDeclaration<?> owner) {
        if (t == null) return null;
        if (t instanceof ClassOrInterfaceType ci) {
            return ci.getNameAsString();
        }
        if (t.isPrimitiveType()) {
            return t.asPrimitiveType().asString();
        }
        if (t.isArrayType()) {
            return t.asArrayType().getElementType().toString() + "[]";
        }
        if (t.isVarType()) {
            return "Object";
        }
        return t.toString();
    }

    private String erasedOfDesc(String desc) {
        if (desc.length() == 1) {
            return switch (desc) {
                case "I" -> "int";
                case "J" -> "long";
                case "Z" -> "boolean";
                case "B" -> "byte";
                case "C" -> "char";
                case "S" -> "short";
                case "F" -> "float";
                case "D" -> "double";
                case "V" -> "void";
                default -> "?";
            };
        }
        if (desc.startsWith("[")) {
            String inner = desc.substring(desc.indexOf('[') + 1);
            String suffix = "[".repeat(desc.lastIndexOf('[') + 1);
            return erasedOfDesc(inner) + suffix;
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            String internal = desc.substring(1, desc.length() - 1);
            int slash = internal.lastIndexOf('/');
            String simple = slash < 0 ? internal : internal.substring(slash + 1);
            return simple.replace('$', '.');
        }
        return desc;
    }
}
