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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChaResolverTest {

    @Test
    void subtype_closure_walks_children() {
        Map<Fqn, Fqn> parent = Map.of(
                Fqn.fromDotted("com.acme.B"), Fqn.fromDotted("com.acme.A"),
                Fqn.fromDotted("com.acme.C"), Fqn.fromDotted("com.acme.B"),
                Fqn.fromDotted("com.acme.D"), Fqn.fromDotted("com.acme.C")
        );
        ChaResolver cha = new ChaResolver(parent, Set.of(), Set.of());
        List<Fqn> closure = cha.subtypeClosure(Fqn.fromDotted("com.acme.A"));
        assertThat(closure).extracting(Fqn::dotted).containsExactlyInAnyOrder(
                "com.acme.A", "com.acme.B", "com.acme.C", "com.acme.D");
    }

    @Test
    void empty_resolver_returns_just_root() {
        List<Fqn> closure = ChaResolver.empty().resolveVirtual(Fqn.fromDotted("com.acme.Foo"));
        assertThat(closure).containsExactly(Fqn.fromDotted("com.acme.Foo"));
    }

    @Test
    void resolve_virtual_returns_all_subclasses() {
        Map<Fqn, Fqn> parent = Map.of(
                Fqn.fromDotted("com.acme.Bar"), Fqn.fromDotted("com.acme.Foo"),
                Fqn.fromDotted("com.acme.Baz"), Fqn.fromDotted("com.acme.Foo")
        );
        ChaResolver cha = new ChaResolver(parent, Set.of(), Set.of());
        List<Fqn> resolved = cha.resolveVirtual(Fqn.fromDotted("com.acme.Foo"));
        assertThat(resolved).extracting(Fqn::dotted)
                .containsExactlyInAnyOrder("com.acme.Foo", "com.acme.Bar", "com.acme.Baz");
    }

    @Test
    void interface_recognized() {
        ChaResolver cha = new ChaResolver(Map.of(), Set.of(Fqn.fromDotted("java.util.List")), Set.of());
        assertThat(cha.isInterface(Fqn.fromDotted("java.util.List"))).isTrue();
        assertThat(cha.isInterface(Fqn.fromDotted("java.util.ArrayList"))).isFalse();
    }
}
