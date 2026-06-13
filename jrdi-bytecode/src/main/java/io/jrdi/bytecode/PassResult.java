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
