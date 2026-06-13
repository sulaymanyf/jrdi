package io.jrdi.bytecode;

import io.jrdi.core.edge.Confidence;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-method ASM visitor. Captures:
 * <ul>
 *   <li>Invoke edges (caller method index, callee owner/name/desc, kind, line, confidence)</li>
 *   <li>Lambda synthetic links via {@link LambdaMetaFactoryHandler}</li>
 *   <li>Reflection edges via a small {@link ReflectionFrame} (constant-string only)</li>
 *   <li>Line numbers: min/max recorded as the method's start/end</li>
 * </ul>
 */
final class MethodVisitorEx extends MethodVisitor {

    private final ClassVisitorEx owner;
    private final int methodIndex;
    private final String name;
    private final String desc;

    private Integer startLine;
    private Integer endLine;
    private int currentLine = -1;

    private final Deque<Object> stack = new ArrayDeque<>();
    private final Map<String, String> lastLoadedClass = new HashMap<>();

    MethodVisitorEx(ClassVisitorEx owner, int methodIndex, String name, String desc) {
        super(Opcodes.ASM9);
        this.owner = owner;
        this.methodIndex = methodIndex;
        this.name = name;
        this.desc = desc;
    }

    @Override
    public void visitLineNumber(int line, org.objectweb.asm.Label start) {
        if (currentLine < 0) currentLine = line;
        if (startLine == null) startLine = line;
        if (endLine == null || line > endLine) endLine = line;
        this.currentLine = line;
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Push a constant onto our model stack for reflection analysis
        stack.push(value);
    }

    @Override
    public void visitInsn(int opcode) {
        // Pops / dups handled implicitly by call sequences; we only react to specific opcodes.
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        // Treat ICONST_0..ICONST_5 as pushing Integer 0..5 (rough but useful for Class.forName
        // patterns that build the name from a literal).
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        recordInvoke(opcode, owner, name, descriptor, isInterface);
        consumeStackForArgs(descriptor);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsmHandle,
                                       Object... bsmArgs) {
        // 1. Generic invokedynamic edge — caller index, callee derived from bsm implMethod
        if (LambdaMetaFactoryHandler.isLambdaSite(bsmHandle)) {
            if (bsmArgs.length >= 2 && bsmArgs[1] instanceof Handle implMethod) {
                String bsmTarget = LambdaMetaFactoryHandler.describeHandle(implMethod);
                owner.recordLambda(new PassResult.LambdaInfo(
                        methodIndex, -1, bsmTarget, currentLine < 0 ? null : currentLine));
                int syntheticIdx = resolveSyntheticIndex(implMethod);
                owner.recordInvoke(new PassResult.InvokeEdge(
                        methodIndex,
                        implMethod.getOwner(),
                        implMethod.getName(),
                        implMethod.getDesc(),
                        PassResult.Kind.DYNAMIC,
                        currentLine < 0 ? null : currentLine,
                        Confidence.CERTAIN,
                        bsmTarget));
                if (syntheticIdx >= 0) {
                    owner.recordLambda(new PassResult.LambdaInfo(
                            methodIndex, syntheticIdx, bsmTarget, currentLine < 0 ? null : currentLine));
                }
            }
        } else {
            // Generic invokedynamic — record a DYNAMIC edge with the bsm owner/name as callee
            owner.recordInvoke(new PassResult.InvokeEdge(
                    methodIndex,
                    bsmHandle.getOwner(),
                    bsmHandle.getName(),
                    bsmHandle.getDesc(),
                    PassResult.Kind.DYNAMIC,
                    currentLine < 0 ? null : currentLine,
                    Confidence.PROBABLE,
                    LambdaMetaFactoryHandler.describeHandle(bsmHandle)));
        }
        consumeStackForArgs(descriptor);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        // GETSTATIC on a class literal pushes a Class<?> onto the stack; capture for reflection.
        if (opcode == Opcodes.GETSTATIC) {
            stack.push("L:" + owner);
        }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        // NEW pushes a half-initialized object — treat as opaque
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        // ignore
    }

    @Override
    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
        // ignore
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (opcode == Opcodes.SIPUSH || opcode == Opcodes.BIPUSH) {
            stack.push(operand);
        }
    }

    @Override
    public void visitEnd() {
        if (startLine != null || endLine != null) {
            owner.recordMethodStartEnd(methodIndex, startLine, endLine);
        }
    }

    private int resolveSyntheticIndex(Handle implMethod) {
        // We don't have an index for the synthetic method (it's defined in the same class but
        // wasn't visited yet at this point). The pipeline pass stitches indices later. For U3
        // we record -1 and let the post-process fix it up.
        return -1;
    }

    private void recordInvoke(int opcode, String calleeOwner, String calleeName, String calleeDesc, boolean isInterface) {
        // Reflection check (matches Class.forName STATIC, loadClass STATIC, Method.invoke VIRTUAL, etc.)
        ReflectionFrame.ReflectiveCall reflection = tryRecognizeReflection(calleeOwner, calleeName, calleeDesc);
        if (reflection != null) {
            if (reflection.resolved()) {
                owner.recordInvoke(new PassResult.InvokeEdge(
                        methodIndex,
                        reflection.internalName(),
                        "<reflected>",
                        "()V",
                        PassResult.Kind.REFLECT,
                        currentLine < 0 ? null : currentLine,
                        Confidence.PROBABLE,
                        reflection.kind()));
            } else {
                owner.recordInvoke(new PassResult.InvokeEdge(
                        methodIndex,
                        "?",
                        "?",
                        "?",
                        PassResult.Kind.REFLECT,
                        currentLine < 0 ? null : currentLine,
                        Confidence.UNCERTAIN,
                        reflection.kind()));
            }
            consumeStackForArgs(calleeDesc);
            return;
        }

        PassResult.Kind kind = switch (opcode) {
            case Opcodes.INVOKESTATIC -> PassResult.Kind.STATIC;
            case Opcodes.INVOKEINTERFACE -> PassResult.Kind.INTERFACE;
            case Opcodes.INVOKESPECIAL -> PassResult.Kind.SPECIAL;
            case Opcodes.INVOKEVIRTUAL -> PassResult.Kind.VIRTUAL;
            default -> PassResult.Kind.VIRTUAL;
        };

        owner.recordInvoke(new PassResult.InvokeEdge(
                methodIndex,
                calleeOwner,
                calleeName,
                calleeDesc,
                kind,
                currentLine < 0 ? null : currentLine,
                Confidence.CERTAIN,
                null));
    }

    private ReflectionFrame.ReflectiveCall tryRecognizeReflection(String owner, String name, String desc) {
        if ("java/lang/Class".equals(owner) && "forName".equals(name)) {
            // Stack has 1 argument: a String class name
            Object arg = pop();
            if (arg instanceof String s && !s.isEmpty()) {
                return new ReflectionFrame.ReflectiveCall(s, "Class.forName", true);
            }
            return new ReflectionFrame.ReflectiveCall(null, "Class.forName", false);
        }
        if ("java/lang/ClassLoader".equals(owner) && "loadClass".equals(name)) {
            Object arg = pop();
            if (arg instanceof String s && !s.isEmpty()) {
                return new ReflectionFrame.ReflectiveCall(s, "ClassLoader.loadClass", true);
            }
            return new ReflectionFrame.ReflectiveCall(null, "ClassLoader.loadClass", false);
        }
        if ("java/lang/reflect/Method".equals(owner) && "invoke".equals(name)) {
            // The first argument is a target (which we rarely can identify without context).
            return new ReflectionFrame.ReflectiveCall(null, "Method.invoke", false);
        }
        if ("java/lang/reflect/Constructor".equals(owner) && "newInstance".equals(name)) {
            return new ReflectionFrame.ReflectiveCall(null, "Constructor.newInstance", false);
        }
        return null;
    }

    private void consumeStackForArgs(String desc) {
        int argc = argCount(desc);
        for (int i = 0; i < argc; i++) pop();
    }

    private static int argCount(String desc) {
        if (desc == null) return 0;
        int open = desc.indexOf('(');
        int close = desc.indexOf(')');
        if (open < 0 || close < 0) return 0;
        String args = desc.substring(open + 1, close);
        int n = 0;
        int i = 0;
        while (i < args.length()) {
            char c = args.charAt(i);
            if (c == 'L') {
                int semi = args.indexOf(';', i);
                i = semi + 1;
            } else {
                i++;
            }
            n++;
        }
        return n;
    }

    private Object pop() {
        return stack.isEmpty() ? null : stack.pop();
    }
}
