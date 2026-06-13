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

import io.jrdi.core.edge.Confidence;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Materialised call graph: vertices are {@link MethodRef}s, edges are resolved
 * {@link CallEdge}s (one per concrete callee — a virtual call on a parent type expands
 * into N edges, one per subclass in CHA's hierarchy).
 *
 * <p>Storage is in-memory; the P6 pipeline materialises this from the {@code invokes} and
 * {@code classes} tables and serves the BFS queries the MCP tool surface needs.
 */
public final class CallGraph {

    private final Set<MethodRef> vertices;
    private final List<CallEdge> edges;

    public CallGraph(Set<MethodRef> vertices, List<CallEdge> edges) {
        this.vertices = Collections.unmodifiableSet(new HashSet<>(vertices));
        this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
    }

    public Set<MethodRef> vertices() {
        return vertices;
    }

    public List<CallEdge> edges() {
        return edges;
    }

    /**
     * Out-edges of a method. Returns the raw (un-resolved) edges — the caller decides
     * whether to expand a virtual edge into all subclass candidates.
     */
    public List<CallEdge> outEdges(MethodRef from) {
        List<CallEdge> out = new ArrayList<>();
        for (CallEdge e : edges) {
            if (e.from().equals(from)) out.add(e);
        }
        return out;
    }

    /**
     * In-edges of a method (callers). Used by {@code callers_of}.
     */
    public List<CallEdge> inEdges(MethodRef to) {
        List<CallEdge> in = new ArrayList<>();
        for (CallEdge e : edges) {
            if (e.to().equals(to)) in.add(e);
        }
        return in;
    }

    /**
     * One resolved edge in the call graph. From/To are both {@link MethodRef}s — virtual
     * ambiguity is expressed as N parallel edges with the same {@code from} but different
     * {@code to}, all marked with {@code confidence = PROBABLE}.
     */
    public record CallEdge(
            MethodRef from,
            MethodRef to,
            Confidence confidence,
            Integer line,
            String note
    ) {
        public static CallEdge staticCall(MethodRef from, MethodRef to, Integer line) {
            return new CallEdge(from, to, Confidence.CERTAIN, line, null);
        }

        public static CallEdge virtual(MethodRef from, MethodRef to, Integer line) {
            return new CallEdge(from, to, Confidence.PROBABLE, line, "CHA");
        }
    }
}
