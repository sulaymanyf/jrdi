package io.jrdi.callgraph;

import io.jrdi.core.symbol.Fqn;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BfsPathFinderTest {

    private static final MethodRef A_RUN = new MethodRef(Fqn.fromDotted("com.acme.A"),
            new io.jrdi.core.symbol.MethodKey("run", "()V"));
    private static final MethodRef B_RUN = new MethodRef(Fqn.fromDotted("com.acme.B"),
            new io.jrdi.core.symbol.MethodKey("run", "()V"));
    private static final MethodRef C_RUN = new MethodRef(Fqn.fromDotted("com.acme.C"),
            new io.jrdi.core.symbol.MethodKey("run", "()V"));
    private static final MethodRef D_RUN = new MethodRef(Fqn.fromDotted("com.acme.D"),
            new io.jrdi.core.symbol.MethodKey("run", "()V"));

    @Test
    void finds_shortest_path() {
        CallGraph graph = new CallGraph(
                Set.of(A_RUN, B_RUN, C_RUN, D_RUN),
                List.of(
                        new CallGraph.CallEdge(A_RUN, B_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 1, null),
                        new CallGraph.CallEdge(B_RUN, C_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 2, null),
                        new CallGraph.CallEdge(C_RUN, D_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 3, null)
                ));
        Optional<List<MethodRef>> path = new BfsPathFinder(graph).findPath(A_RUN, D_RUN, 10);
        assertThat(path).isPresent();
        assertThat(path.get()).hasSize(4);
        assertThat(path.get().get(0)).isEqualTo(A_RUN);
        assertThat(path.get().get(3)).isEqualTo(D_RUN);
    }

    @Test
    void respects_max_depth() {
        CallGraph graph = new CallGraph(
                Set.of(A_RUN, B_RUN, C_RUN),
                List.of(
                        new CallGraph.CallEdge(A_RUN, B_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 1, null),
                        new CallGraph.CallEdge(B_RUN, C_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 2, null)
                ));
        Optional<List<MethodRef>> path = new BfsPathFinder(graph).findPath(A_RUN, C_RUN, 1);
        assertThat(path).isEmpty();
    }

    @Test
    void no_path_returns_empty() {
        CallGraph graph = new CallGraph(
                Set.of(A_RUN, B_RUN),
                List.of(
                        new CallGraph.CallEdge(A_RUN, B_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 1, null)
                ));
        Optional<List<MethodRef>> path = new BfsPathFinder(graph).findPath(B_RUN, A_RUN, 5);
        assertThat(path).isEmpty();
    }

    @Test
    void from_equals_to_returns_singleton() {
        CallGraph graph = new CallGraph(Set.of(A_RUN), List.of());
        Optional<List<MethodRef>> path = new BfsPathFinder(graph).findPath(A_RUN, A_RUN, 5);
        assertThat(path).isPresent();
        assertThat(path.get()).containsExactly(A_RUN);
    }

    @Test
    void handles_cycles() {
        Set<MethodRef> verts = new HashSet<>(Set.of(A_RUN, B_RUN));
        CallGraph graph = new CallGraph(verts, List.of(
                new CallGraph.CallEdge(A_RUN, B_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 1, null),
                new CallGraph.CallEdge(B_RUN, A_RUN, io.jrdi.core.edge.Confidence.CERTAIN, 2, null)
        ));
        Optional<List<MethodRef>> path = new BfsPathFinder(graph).findPath(A_RUN, B_RUN, 10);
        assertThat(path).isPresent();
        assertThat(path.get()).containsExactly(A_RUN, B_RUN);
    }
}
