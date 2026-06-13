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
import io.jrdi.core.symbol.MethodKey;

import java.util.Objects;

/**
 * Stable identifier for a method in the call graph: a pair of {@link Fqn} (the owner class)
 * and {@link MethodKey} (name + erased descriptor). Used as the graph's vertex id.
 */
public record MethodRef(Fqn owner, MethodKey key) {

    public MethodRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
    }

    public String dashed() {
        return owner.slashed() + "#" + key.name() + key.descriptor();
    }

    public static MethodRef of(String ownerSlashed, String name, String desc) {
        return new MethodRef(Fqn.of(ownerSlashed), new MethodKey(name, desc));
    }
}
