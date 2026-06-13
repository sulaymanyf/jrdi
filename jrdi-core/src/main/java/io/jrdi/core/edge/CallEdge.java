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
 */package io.jrdi.core.edge;

import io.jrdi.core.symbol.MethodKey;
import io.jrdi.core.symbol.SymbolRef;

import java.util.Objects;

/**
 * A directed call relationship. {@code from} is always a concrete method (bytecode fact);
 * {@code to} may be a class (for virtual dispatch) or a method (static / invoke-special).
 */
public final class CallEdge {

    public enum Kind {
        VIRTUAL, STATIC, SPECIAL, INTERFACE, DYNAMIC, REFLECT, DUBBO, SPRING_INJECT, SQL_BIND
    }

    private final SymbolRef from;
    private final SymbolRef to;
    private final Kind kind;
    private final Confidence confidence;
    private final int line;
    private final String note;

    private CallEdge(SymbolRef from, SymbolRef to, Kind kind, Confidence confidence, int line, String note) {
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
        this.kind = Objects.requireNonNull(kind);
        this.confidence = Objects.requireNonNull(confidence);
        this.line = line;
        this.note = note;
    }

    public static CallEdge virtual(SymbolRef from, SymbolRef to, int line) {
        return new CallEdge(from, to, Kind.VIRTUAL, Confidence.CERTAIN, line, null);
    }

    public static CallEdge staticCall(SymbolRef from, SymbolRef to, int line) {
        return new CallEdge(from, to, Kind.STATIC, Confidence.CERTAIN, line, null);
    }

    public static CallEdge special(SymbolRef from, SymbolRef to, int line) {
        return new CallEdge(from, to, Kind.SPECIAL, Confidence.CERTAIN, line, null);
    }

    public static CallEdge interfaceCall(SymbolRef from, SymbolRef to, int line) {
        return new CallEdge(from, to, Kind.INTERFACE, Confidence.CERTAIN, line, null);
    }

    public static CallEdge dynamic(SymbolRef from, SymbolRef to, int line, MethodKey synthetic) {
        return new CallEdge(from, to, Kind.DYNAMIC, Confidence.CERTAIN, line,
                "synthetic=" + synthetic.toString());
    }

    public static CallEdge reflect(SymbolRef from, SymbolRef to, Confidence confidence, int line, String note) {
        return new CallEdge(from, to, Kind.REFLECT, confidence, line, note);
    }

    public static CallEdge dubbo(SymbolRef from, SymbolRef to, Confidence confidence, int line) {
        return new CallEdge(from, to, Kind.DUBBO, confidence, line, null);
    }

    public SymbolRef from() {
        return from;
    }

    public SymbolRef to() {
        return to;
    }

    public Kind kind() {
        return kind;
    }

    public Confidence confidence() {
        return confidence;
    }

    public int line() {
        return line;
    }

    public String note() {
        return note;
    }
}
