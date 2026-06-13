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
 */package io.jrdi.callgraph;

import io.jrdi.core.symbol.Fqn;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a class FQN to its declared members by reading from sources OUTSIDE the
 * indexed database — typically the local m2 cache, a classpath directory, or a
 * downloaded jar.
 *
 * <p>The {@link ChaResolver} uses an {@code ExternalClassResolver} as a fallback when
 * a parent FQN is not in the in-memory hierarchy built from the indexed {@code classes}
 * table. This is what makes cross-jar CHA work: when class B (indexed) extends class A
 * (in a 3rd-party jar), and B's call to {@code A.foo()} is being expanded, CHA needs to
 * know that {@code A.foo()} exists even though A isn't in our DB.
 *
 * <p>Resolvers should be cheap and idempotent. Implementations typically cache reads
 * per FQN in a {@code ConcurrentHashMap}.
 */
public interface ExternalClassResolver {

    /**
     * Look up a single class by FQN. Returns the minimal info CHA needs to expand
     * virtual/interface invokes: the supertype, the implemented interfaces, and the
     * declared methods. Returns empty when the class cannot be found.
     */
    Optional<ExternalClass> resolve(Fqn fqn);

    /**
     * Snapshot of one class as seen from outside our DB. {@code superFqn} and
     * {@code interfaces} are used to extend the hierarchy; {@code methods} are the
     * declared methods on the class itself (NOT inherited from supertypes).
     */
    record ExternalClass(
            Fqn fqn,
            Fqn superFqn,
            List<Fqn> interfaces,
            List<MethodSig> methods) {
    }

    /**
     * A single declared method on an external class. {@code access} is the JVM
     * access flags bitmask (ACC_PUBLIC = 0x0001, ACC_PROTECTED = 0x0004, etc.).
     */
    record MethodSig(String name, String desc, int access) {
    }
}
