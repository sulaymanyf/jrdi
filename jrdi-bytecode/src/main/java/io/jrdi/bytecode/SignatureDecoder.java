package io.jrdi.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a JVM-internal type signature (class / method / field Signature attribute) into a
 * compact, JSON-able tree of {@link TypeNode}. Keeps the structure inspectable by the storage
 * layer and by the MCP "describe_method" tool surface.
 */
public final class SignatureDecoder {

    private SignatureDecoder() {
    }

    public static String decodeClassSignature(String signature) {
        if (signature == null) return null;
        StringBuilder out = new StringBuilder();
        SignatureReader r = new SignatureReader(signature);
        r.accept(new ClassSignatureVisitor(out));
        return out.toString();
    }

    public static String decodeMethodSignature(String signature) {
        if (signature == null) return null;
        StringBuilder out = new StringBuilder();
        SignatureReader r = new SignatureReader(signature);
        r.accept(new MethodSignatureVisitor(out));
        return out.toString();
    }

    public static String decodeFieldSignature(String signature) {
        if (signature == null) return null;
        StringBuilder out = new StringBuilder();
        new SignatureReader(signature).acceptType(new TypeVisitor("field", out));
        return out.toString();
    }

    /**
     * A flattened node in the decoded signature tree. Each level is a type argument
     * (T extends X, ? super Y, ? extends Y, etc.). {@code raw} is the erased FQN.
     */
    public record TypeNode(String raw, List<TypeArg> args) {}

    public record TypeArg(String wildcard, TypeNode bound) {}

    private static String rawType(String internal) {
        if (internal == null || internal.isEmpty()) return internal;
        return internal.replace('/', '.');
    }

    private static final class ClassSignatureVisitor extends SignatureVisitor {
        private final StringBuilder out;

        ClassSignatureVisitor(StringBuilder out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            out.append(name);
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return new TypeVisitor("super", out);
        }

        @Override
        public SignatureVisitor visitInterface() {
            return new TypeVisitor("interface", out);
        }
    }

    private static final class MethodSignatureVisitor extends SignatureVisitor {
        private final StringBuilder out;

        MethodSignatureVisitor(StringBuilder out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            out.append(name);
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return new TypeVisitor("param", out);
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return new TypeVisitor("returns", out);
        }
    }

    private static final class TypeVisitor extends SignatureVisitor {
        private final String label;
        private final StringBuilder out;

        TypeVisitor(String label, StringBuilder out) {
            super(Opcodes.ASM9);
            this.label = label;
            this.out = out;
        }

        @Override
        public void visitClassType(String name) {
            if (out.length() > 0) out.append(",");
            out.append(label).append('=').append(rawType(name));
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new TypeArgVisitor(wildcard, out);
        }
    }

    private static final class TypeArgVisitor extends SignatureVisitor {
        private final char wildcard;
        private final StringBuilder out;

        TypeArgVisitor(char wildcard, StringBuilder out) {
            super(Opcodes.ASM9);
            this.wildcard = wildcard;
            this.out = out;
        }

        @Override
        public void visitClassType(String name) {
            if (out.length() > 0) out.append(",");
            out.append('[');
            if (wildcard == '+') out.append("? extends ");
            else if (wildcard == '-') out.append("? super ");
            else if (wildcard == '*') out.append('*');
            out.append(rawType(name)).append(']');
        }
    }
}
