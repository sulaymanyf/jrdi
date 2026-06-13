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
import io.jrdi.storage.repo.InvokeRepo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeExpanderTest {

    private static final MethodRef A = new MethodRef(Fqn.fromDotted("com.acme.A"),
            new MethodKey("do", "()V"));
    private static final MethodRef B = new MethodRef(Fqn.fromDotted("com.acme.B"),
            new MethodKey("do", "()V"));
    private static final MethodRef C = new MethodRef(Fqn.fromDotted("com.acme.C"),
            new MethodKey("do", "()V"));

    @Test
    void virtual_invocation_expands_to_subclass_closure() {
        ChaResolver cha = new ChaResolver(
                Map.of(
                        Fqn.fromDotted("com.acme.B"), Fqn.fromDotted("com.acme.A"),
                        Fqn.fromDotted("com.acme.C"), Fqn.fromDotted("com.acme.A")
                ),
                Set.of(), Set.of());
        EdgeExpander expander = new EdgeExpander(cha);
        long callerId = 1L;
        expander.registerCaller(callerId, A);
        InvokeRepo.Edge raw = new InvokeRepo.Edge(
                callerId, "com/acme/A", "do", "()V",
                InvokeRepo.Kind.VIRTUAL, 10, InvokeRepo.Confidence.CERTAIN);
        List<CallGraph.CallEdge> edges = expander.expand(raw);
        assertThat(edges).hasSize(3);
        assertThat(edges).allSatisfy(e -> {
            assertThat(e.from()).isEqualTo(A);
            assertThat(e.confidence()).isEqualTo(io.jrdi.core.edge.Confidence.PROBABLE);
            assertThat(e.note()).isEqualTo("CHA");
        });
        assertThat(edges).extracting(e -> e.to().owner().dotted())
                .containsExactlyInAnyOrder("com.acme.A", "com.acme.B", "com.acme.C");
    }

    @Test
    void static_invocation_single_target_with_certain_confidence() {
        EdgeExpander expander = new EdgeExpander(ChaResolver.empty());
        long callerId = 1L;
        expander.registerCaller(callerId, A);
        InvokeRepo.Edge raw = new InvokeRepo.Edge(
                callerId, "java/lang/Math", "max", "(II)I",
                InvokeRepo.Kind.STATIC, 1, InvokeRepo.Confidence.CERTAIN);
        List<CallGraph.CallEdge> edges = expander.expand(raw);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).to().owner().dotted()).isEqualTo("java.lang.Math");
        assertThat(edges.get(0).confidence()).isEqualTo(io.jrdi.core.edge.Confidence.CERTAIN);
    }

    @Test
    void special_invocation_single_target() {
        EdgeExpander expander = new EdgeExpander(ChaResolver.empty());
        expander.registerCaller(1L, A);
        InvokeRepo.Edge raw = new InvokeRepo.Edge(
                1L, "com/acme/A", "<init>", "()V",
                InvokeRepo.Kind.SPECIAL, 1, InvokeRepo.Confidence.CERTAIN);
        List<CallGraph.CallEdge> edges = expander.expand(raw);
        assertThat(edges).hasSize(1);
    }

    @Test
    void reflect_invocation_emits_no_edges() {
        EdgeExpander expander = new EdgeExpander(ChaResolver.empty());
        expander.registerCaller(1L, A);
        InvokeRepo.Edge raw = new InvokeRepo.Edge(
                1L, "?", "?", "?",
                InvokeRepo.Kind.REFLECT, 1, InvokeRepo.Confidence.UNCERTAIN);
        List<CallGraph.CallEdge> edges = expander.expand(raw);
        assertThat(edges).isEmpty();
    }

    @Test
    void build_graph_collects_vertices_and_edges() {
        ChaResolver cha = new ChaResolver(
                Map.of(Fqn.fromDotted("com.acme.B"), Fqn.fromDotted("com.acme.A")),
                Set.of(), Set.of());
        EdgeExpander expander = new EdgeExpander(cha);
        expander.registerCaller(1L, A);
        expander.registerCaller(2L, B);
        CallGraph graph = expander.buildGraph(Set.of(A), List.of(
                new InvokeRepo.Edge(1L, "com/acme/A", "do", "()V",
                        InvokeRepo.Kind.VIRTUAL, 1, InvokeRepo.Confidence.CERTAIN)
        ));
        // A -> A (self) and A -> B (subclass)
        assertThat(graph.vertices()).extracting(v -> v.owner().dotted())
                .contains("com.acme.A", "com.acme.B");
        assertThat(graph.edges().size()).isGreaterThanOrEqualTo(2);
    }
}
