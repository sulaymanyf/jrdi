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
 */package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ASM {@link ClassVisitor} that collects, per class:
 * <ul>
 *   <li>Class-level facts (fqn, access, super, signature)</li>
 *   <li>Method records with their line number ranges (from LineNumberTable when present)</li>
 *   <li>Field records</li>
 *   <li>Invoke edges (per-method), captured by {@link MethodVisitorEx}</li>
 *   <li>Lambda links (enclosing method ↔ synthetic target)</li>
 *   <li>Nested classes (for class-level ownership queries in P2)</li>
 * </ul>
 */
final class ClassVisitorEx extends ClassVisitor {

    private final List<PassResult.ClassInfo> nestedClasses = new ArrayList<>();
    private final List<PassResult.MethodInfo> methods = new ArrayList<>();
    private final List<PassResult.FieldInfo> fields = new ArrayList<>();
    private final List<PassResult.InvokeEdge> invokes = new ArrayList<>();
    private final List<PassResult.LambdaInfo> lambdas = new ArrayList<>();

    private String name;
    private int access;
    private String superName;
    private final java.util.List<io.jrdi.core.symbol.Fqn> interfaces = new java.util.ArrayList<>();
    private String signature;
    private Fqn outerClass;
    private final List<MethodVisitorEx> activeMethods = new ArrayList<>();

    ClassVisitorEx() {
        super(Opcodes.ASM9);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.name = name;
        this.access = access;
        this.signature = signature;
        this.superName = superName;
        if (interfaces != null) {
            for (String i : interfaces) {
                if (i != null && !i.isEmpty()) {
                    this.interfaces.add(io.jrdi.core.symbol.Fqn.of(i.replace('.', '/')));
                }
            }
        }
        // The outer-class attribute (if any) is reported by visitOuterClass below.
    }

    @Override
    public void visitOuterClass(String owner, String name, String descriptor) {
        if (owner != null) {
            this.outerClass = Fqn.of(owner);
        }
    }

    @Override
    public void visitNestHost(String nestHost) {
        // Could be used later for record-like types; ignored in U3.
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        nestedClasses.add(new PassResult.ClassInfo(
                Fqn.of(name),
                access,
                outerName == null ? Optional.empty() : Optional.of(Fqn.of(outerName))
        ));
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        int idx = methods.size();
        boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
        boolean bridge = (access & Opcodes.ACC_BRIDGE) != 0;
        methods.add(new PassResult.MethodInfo(idx, name, descriptor, signature, null, null, synthetic, bridge, false));
        MethodVisitorEx mv = new MethodVisitorEx(this, idx, name, descriptor);
        activeMethods.add(mv);
        return mv;
    }

    @Override
    public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        fields.add(new PassResult.FieldInfo(name, descriptor, signature, null));
        return null;
    }

    @Override
    public void visitEnd() {
        // done — handlers flushed
    }

    void recordMethodStartEnd(int methodIndex, Integer start, Integer end) {
        PassResult.MethodInfo old = methods.get(methodIndex);
        methods.set(methodIndex, new PassResult.MethodInfo(
                methodIndex, old.name(), old.desc(), old.signatureRaw(),
                start, end, old.synthetic(), old.bridge(), old.virtual()));
    }

    void recordFieldLine(String name, int line) {
        for (int i = 0; i < fields.size(); i++) {
            PassResult.FieldInfo f = fields.get(i);
            if (f.name().equals(name)) {
                fields.set(i, new PassResult.FieldInfo(f.name(), f.desc(), f.signatureRaw(), line));
                return;
            }
        }
    }

    void recordInvoke(PassResult.InvokeEdge e) {
        invokes.add(e);
    }

    void recordLambda(PassResult.LambdaInfo l) {
        lambdas.add(l);
    }

    PassResult build() {
        Fqn fqn = name == null ? null : Fqn.of(name);
        return new PassResult(
                fqn,
                access,
                superName == null ? Optional.empty() : Optional.of(Fqn.of(superName)),
                signature,
                java.util.Collections.unmodifiableList(new java.util.ArrayList<>(interfaces)),
                nestedClasses,
                methods,
                fields,
                invokes,
                lambdas
        );
    }
}
