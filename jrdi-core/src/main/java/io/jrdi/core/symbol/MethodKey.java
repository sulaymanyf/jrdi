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

/**
 * Stable identifier for a method. Encodes {@code (name, parameterDescriptors)} where descriptors
 * are the JVM-level erased type descriptors (slashed form), e.g. {@code (Ljava/util/List;Ljava/lang/String;)V}.
 * <p>
 * Two method declarations with the same erased signature map to the same {@code MethodKey} regardless
 * of generic instantiations. This is the canonical primary key for the {@code methods} table.
 */
public final class MethodKey {

    private final String name;
    private final String descriptor;
    private final int hashCode;

    public MethodKey(String name, String descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        this.name = name;
        this.descriptor = descriptor;
        this.hashCode = name.hashCode() * 31 + descriptor.hashCode();
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    /**
     * For example {@code org/acme/Foo#bar(Ljava/util/List;)V}.
     */
    public String asString(Fqn owner) {
        return owner.slashed() + "#" + name + descriptor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodKey that)) return false;
        return name.equals(that.name) && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return name + descriptor;
    }
}
