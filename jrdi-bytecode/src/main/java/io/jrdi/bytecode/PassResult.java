package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;

import java.util.List;
import java.util.Optional;

/**
 * Output of {@link BytecodePass#run(Fqn, byte[])}: the bytecode facts extracted from a single
 * class. The pipeline translates this into row-insertions on the {@code classes / methods /
 * fields / invokes / lambdas} tables.
 *
 * <p>Indices here are <em>local</em> to the class being visited: the caller's responsibility
 * is to map them to global ids when persisting (e.g. {@code methodIndex} → {@code methods.id}).
 */
public record PassResult(
        Fqn fqn,
        int access,
        Optional<Fqn> superFqn,
        String classSignatureRaw,
        List<Fqn> interfaces,

        List<ClassInfo> nestedClasses,
        List<MethodInfo> methods,
        List<FieldInfo> fields,
        List<InvokeEdge> invokes,
        List<LambdaInfo> lambdas
) {

    public record ClassInfo(Fqn fqn, int access, Optional<Fqn> outerFqn) {}

    public record MethodInfo(
            int methodIndex,
            String name,
            String desc,
            String signatureRaw,
            Integer startLine,
            Integer endLine,
            boolean synthetic,
            boolean bridge,
            boolean virtual
    ) {
        public static MethodInfo indexOnly(int methodIndex) {
            return new MethodInfo(methodIndex, "", "", null, null, null, false, false, false);
        }
    }

    public record FieldInfo(
            String name,
            String desc,
            String signatureRaw,
            Integer line
    ) {}

    public record InvokeEdge(
            int methodIndex,
            String calleeOwner,
            String calleeName,
            String calleeDesc,
            Kind kind,
            Integer line,
            io.jrdi.core.edge.Confidence confidence,
            String note
    ) {}

    public record LambdaInfo(
            int enclosingMethodIndex,
            int syntheticMethodIndex,
            String bsmTarget,
            Integer line
    ) {}

    public enum Kind { VIRTUAL, STATIC, SPECIAL, INTERFACE, DYNAMIC, REFLECT }
}
