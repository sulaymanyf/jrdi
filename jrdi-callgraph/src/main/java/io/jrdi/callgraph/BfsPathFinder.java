package io.jrdi.callgraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BFS path finder on a {@link CallGraph}. Stops as soon as {@code maxDepth} levels are
 * explored (the {@code from} vertex counts as depth 0). Returns the first found path; if
 * you want all paths, increase the cap and call repeatedly.
 */
public final class BfsPathFinder {

    private static final Logger LOG = LoggerFactory.getLogger(BfsPathFinder.class);

    private final CallGraph graph;

    public BfsPathFinder(CallGraph graph) {
        this.graph = graph;
    }

    public Optional<List<MethodRef>> findPath(MethodRef from, MethodRef to, int maxDepth) {
        if (maxDepth < 0) return Optional.empty();
        if (from.equals(to)) return Optional.of(List.of(from));

        Set<MethodRef> visited = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(from, List.of(from)));
        visited.add(from);

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            if (current.path.size() > maxDepth) continue;
            for (CallGraph.CallEdge edge : graph.outEdges(current.vertex)) {
                MethodRef next = edge.to();
                if (next.equals(to)) {
                    List<MethodRef> path = new ArrayList<>(current.path);
                    path.add(next);
                    return Optional.of(path);
                }
                if (visited.add(next)) {
                    List<MethodRef> newPath = new ArrayList<>(current.path);
                    newPath.add(next);
                    queue.add(new Node(next, newPath));
                }
            }
        }
        LOG.debug("no path from {} to {} within depth {}", from, to, maxDepth);
        return Optional.empty();
    }

    private record Node(MethodRef vertex, List<MethodRef> path) {}
}
