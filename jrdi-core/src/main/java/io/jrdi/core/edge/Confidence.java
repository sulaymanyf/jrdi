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

/**
 * Confidence levels attached to every edge emitted by analyzers. The semantic is:
 * <ul>
 *   <li>{@link #CERTAIN}: bytecode-level direct call, no dynamic dispatch ambiguity.</li>
 *   <li>{@link #PROBABLE}: static-resolvable but with runtime ambiguity (CHA, framework routing, generic-erased match).</li>
 *   <li>{@link #UNCERTAIN}: reflection, dynamic proxy, non-constant argument, conditional framework setup.</li>
 * </ul>
 */
public enum Confidence {
    CERTAIN(3),
    PROBABLE(2),
    UNCERTAIN(1);

    private final int weight;

    Confidence(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public Confidence atLeast(Confidence other) {
        return this.weight >= other.weight ? this : other;
    }

    public Confidence max(Confidence other) {
        return this.weight >= other.weight ? this : other;
    }
}
