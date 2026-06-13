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
import io.jrdi.storage.repo.InvokeRepo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a raw invoke (the row from the {@code invokes} table) into one or more
 * {@link CallGraph.CallEdge}s by consulting {@link ChaResolver} for the virtual/interface
 * cases.
 *
 * <p>For example, an invoke of type {@code VIRTUAL} on {@code com/acme/Foo#bar()} produces
 * one edge per subclass in the hierarchy. {@code STATIC} / {@code SPECIAL} / {@code DYNAMIC}
 * produce a single edge.
 */
public final class EdgeExpander {

    private final ChaResolver cha;
    private final Map<Long, MethodRef> callerIndex = new HashMap<>();

    public EdgeExpander(ChaResolver cha) {
        this.cha = cha;
    }

    public void registerCaller(long methodId, MethodRef ref) {
        callerIndex.put(methodId, ref);
    }

    public List<CallGraph.CallEdge> expand(InvokeRepo.Edge edge) {
        MethodRef caller = callerIndex.get(edge.callerMethodId());
        if (caller == null) return List.of();
        return expand(edge, caller);
    }

    public List<CallGraph.CallEdge> expand(InvokeRepo.Edge edge, MethodRef callerRef) {
        InvokeRepo.Kind kind = edge.kind();
        InvokeRepo.Confidence conf = edge.confidence();
        String owner = edge.calleeOwner();
        String name = edge.calleeName();
        String desc = edge.calleeDesc();
        Fqn calleeOwner = Fqn.of(owner);
        MethodKey calleeKey = new MethodKey(name, desc);
        MethodRef singleTarget = new MethodRef(calleeOwner, calleeKey);

        return switch (kind) {
            case STATIC, SPECIAL, INTERFACE -> List.of(
                    new CallGraph.CallEdge(callerRef, singleTarget, toConf(conf), edge.line(), null));
            case VIRTUAL -> {
                List<Fqn> closure = cha.resolveVirtual(calleeOwner);
                List<CallGraph.CallEdge> out = new ArrayList<>(closure.size());
                for (Fqn sub : closure) {
                    MethodRef tgt = new MethodRef(sub, calleeKey);
                    out.add(new CallGraph.CallEdge(callerRef, tgt, lowerForCha(conf), edge.line(), "CHA"));
                }
                yield out;
            }
            case DYNAMIC -> List.of(new CallGraph.CallEdge(
                    callerRef, singleTarget, toConf(conf), edge.line(), "dynamic"));
            case REFLECT, DUBBO, SQL_BIND, SPRING_INJECT -> List.of();
        };
    }

    public CallGraph buildGraph(Set<MethodRef> vertices, List<InvokeRepo.Edge> rawEdges) {
        Set<MethodRef> vs = new HashSet<>(vertices);
        List<CallGraph.CallEdge> edges = new ArrayList<>();
        for (InvokeRepo.Edge raw : rawEdges) {
            MethodRef caller = callerIndex.get(raw.callerMethodId());
            if (caller == null) continue;
            for (CallGraph.CallEdge e : expand(raw, caller)) {
                edges.add(e);
                vs.add(caller);
                vs.add(e.to());
            }
        }
        return new CallGraph(vs, edges);
    }

    private static Confidence toConf(InvokeRepo.Confidence c) {
        return switch (c) {
            case CERTAIN -> Confidence.CERTAIN;
            case PROBABLE -> Confidence.PROBABLE;
            case UNCERTAIN -> Confidence.UNCERTAIN;
        };
    }

    private static Confidence lowerForCha(InvokeRepo.Confidence c) {
        return switch (c) {
            case CERTAIN -> Confidence.PROBABLE;
            case PROBABLE -> Confidence.PROBABLE;
            case UNCERTAIN -> Confidence.UNCERTAIN;
        };
    }
}
