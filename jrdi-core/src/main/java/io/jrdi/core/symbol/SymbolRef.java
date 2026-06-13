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
 */package io.jrdi.core.symbol;

import java.util.Objects;
import java.util.Optional;

/**
 * Generic symbol reference used by the storage and analyzers. Discriminated by {@link Kind}.
 */
public final class SymbolRef {

    public enum Kind { CLASS, METHOD, FIELD, PACKAGE }

    private final Kind kind;
    private final Fqn fqn;
    private final String memberName;
    private final String descriptor;

    private SymbolRef(Kind kind, Fqn fqn, String memberName, String descriptor) {
        this.kind = kind;
        this.fqn = fqn;
        this.memberName = memberName;
        this.descriptor = descriptor;
    }

    public static SymbolRef ofClass(Fqn fqn) {
        return new SymbolRef(Kind.CLASS, Objects.requireNonNull(fqn), null, null);
    }

    public static SymbolRef ofClass(String fqn) {
        return ofClass(Fqn.of(fqn));
    }

    public static SymbolRef ofMethod(Fqn owner, String name, String descriptor) {
        return new SymbolRef(Kind.METHOD, Objects.requireNonNull(owner),
                Objects.requireNonNull(name), Objects.requireNonNull(descriptor));
    }

    public static SymbolRef ofField(Fqn owner, String name, String descriptor) {
        return new SymbolRef(Kind.FIELD, Objects.requireNonNull(owner),
                Objects.requireNonNull(name), Objects.requireNonNull(descriptor));
    }

    public Kind kind() {
        return kind;
    }

    public Fqn fqn() {
        return fqn;
    }

    public Optional<String> memberName() {
        return Optional.ofNullable(memberName);
    }

    public Optional<String> descriptor() {
        return Optional.ofNullable(descriptor);
    }

    /**
     * @return the {@link MethodKey} if this is a method reference, else empty.
     */
    public Optional<MethodKey> asMethodKey() {
        if (kind != Kind.METHOD) return Optional.empty();
        return Optional.of(new MethodKey(memberName, descriptor));
    }

    @Override
    public String toString() {
        return switch (kind) {
            case CLASS -> fqn.slashed();
            case METHOD -> fqn.slashed() + "#" + memberName + descriptor;
            case FIELD -> fqn.slashed() + "." + memberName;
            case PACKAGE -> "pkg:" + fqn.slashed();
        };
    }
}
