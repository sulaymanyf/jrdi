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

import io.jrdi.callgraph.ExternalClassResolver.ExternalClass;
import io.jrdi.core.symbol.Fqn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Class Hierarchy Analysis. Given a class graph (FQN → parent + immediate children), an
 * invoke edge {@code (caller, owner, name, desc, kind)} is expanded into N {@link CallEdge}s
 * for virtual/interface dispatch — one per reachable subtype.
 *
 * <p>Inputs are pre-built in U6 from the {@code classes} table; here we operate on an
 * in-memory snapshot for the P1 exit criteria (no DB roundtrips in the BFS path).
 *
 * <p>Optionally a {@link ExternalClassResolver} can be plugged in to handle cross-jar CHA:
 * when a parent FQN is not in the in-memory hierarchy (because the parent is in a 3rd-party
 * jar that wasn't indexed), the resolver falls back to reading the parent's class file
 * from outside the DB (typically the local m2 cache). The found parents are added to the
 * hierarchy as needed.
 */
public final class ChaResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ChaResolver.class);

    private final Map<Fqn, Fqn> parent;       // FQN -> immediate parent
    private final Map<Fqn, List<Fqn>> children; // FQN -> direct subclasses
    private final Set<Fqn> interfaces;         // FQN of known interface types
    private final Set<Fqn> abstractTypes;      // FQN of abstract classes
    private final ExternalClassResolver external; // optional cross-jar fallback

    public ChaResolver(Map<Fqn, Fqn> parent, Set<Fqn> interfaces, Set<Fqn> abstractTypes) {
        this(parent, interfaces, abstractTypes, null);
    }

    public ChaResolver(Map<Fqn, Fqn> parent, Set<Fqn> interfaces, Set<Fqn> abstractTypes,
                       ExternalClassResolver external) {
        this.parent = new HashMap<>(parent);
        this.interfaces = new HashSet<>(interfaces);
        this.abstractTypes = new HashSet<>(abstractTypes);
        this.children = new HashMap<>();
        for (Map.Entry<Fqn, Fqn> e : parent.entrySet()) {
            children.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        this.external = external;
    }

    /**
     * Compute the closure of all subtypes of {@code root} reachable in the hierarchy.
     * Returns {@code [root]} if the type has no children.
     *
     * <p>If the {@code root} FQN is not in the in-memory hierarchy AND a non-null
     * {@code external} resolver is configured, the resolver is consulted. Any
     * newly-discovered parent links are recorded for the lifetime of this resolver
     * (so the next call to {@code subtypeClosure} for a related type is cheap).
     */
    public List<Fqn> subtypeClosure(Fqn root) {
        List<Fqn> out = new ArrayList<>();
        walk(root, new HashSet<>(), out);
        return out;
    }

    private void walk(Fqn current, Set<Fqn> seen, List<Fqn> out) {
        if (!seen.add(current)) return;
        out.add(current);
        // Ensure the parent map is populated for this FQN (may require a cross-jar lookup).
        ensureKnown(current);
        List<Fqn> kids = children.get(current);
        if (kids == null) return;
        for (Fqn k : kids) walk(k, seen, out);
    }

    /**
     * Look up a type's parent. If the FQN is not in the in-memory map and an external
     * resolver is configured, fall back to it. Returns empty if neither knows.
     */
    public Optional<Fqn> parentOf(Fqn fqn) {
        Fqn p = parent.get(fqn);
        if (p != null) return Optional.of(p);
        if (external != null) {
            Optional<ExternalClass> ext = external.resolve(fqn);
            if (ext.isPresent() && ext.get().superFqn() != null) {
                recordExternal(ext.get());
                return Optional.of(ext.get().superFqn());
            }
        }
        return Optional.empty();
    }

    /**
     * Decide which FQN(s) a virtual/interface invoke of {@code declaredOwner} can land on.
     * Returns the closure of subtypes of {@code declaredOwner}.
     */
    public List<Fqn> resolveVirtual(Fqn declaredOwner) {
        return subtypeClosure(declaredOwner);
    }

    public boolean isInterface(Fqn fqn) {
        return interfaces.contains(fqn);
    }

    public boolean isAbstract(Fqn fqn) {
        return abstractTypes.contains(fqn);
    }

    /**
     * Look up a method by name+desc on {@code owner}, walking up the supertype chain via
     * both the in-memory hierarchy and the external resolver. Returns the FQN that declares
     * the method (either {@code owner} or one of its supertypes), or empty if not found.
     * Used by the call-graph edge expander to disambiguate which supertype owns a given
     * method override.
     */
    public Optional<Fqn> declaringType(Fqn owner, String name, String desc) {
        Fqn current = owner;
        Set<Fqn> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            for (ExternalClassResolver.MethodSig m : methodsOn(current)) {
                if (m.name().equals(name) && m.desc().equals(desc)) {
                    return Optional.of(current);
                }
            }
            current = parentOf(current).orElse(null);
        }
        return Optional.empty();
    }

    /** Methods declared on {@code fqn}: in-memory DB methods, or external-resolver methods. */
    public List<ExternalClassResolver.MethodSig> methodsOn(Fqn fqn) {
        // In-memory: a caller can populate a separate method table; for now we lean on
        // the external resolver for the cross-jar path. The pipeline can extend this.
        if (external != null) {
            return external.resolve(fqn)
                    .map(ExternalClass::methods)
                    .orElse(List.of());
        }
        return List.of();
    }

    private void ensureKnown(Fqn fqn) {
        if (parent.containsKey(fqn) || external == null) return;
        external.resolve(fqn).ifPresent(this::recordExternal);
    }

    private synchronized void recordExternal(ExternalClass ext) {
        // Add the supertype link so subtypeClosure works across the external FQN.
        if (ext.superFqn() != null && !parent.containsKey(ext.fqn())) {
            parent.put(ext.fqn(), ext.superFqn());
            children.computeIfAbsent(ext.superFqn(), k -> new ArrayList<>()).add(ext.fqn());
        }
        // Treat external classes as if they could be interfaces (CHA itself doesn't
        // require this — the EdgeExpander does the kind-based dispatch).
        ext.interfaces().forEach(interfaces::add);
    }

    /**
     * Convenience: build an empty resolver when no hierarchy is available. Every virtual
     * call resolves to just the declared type.
     */
    public static ChaResolver empty() {
        return new ChaResolver(Map.of(), Set.of(), Set.of());
    }
}
