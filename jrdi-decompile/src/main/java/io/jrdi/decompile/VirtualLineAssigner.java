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
 */package io.jrdi.decompile;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.source.MethodMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Stitches {@link CfrDecompiler} output back into the {@link MethodMatcher} pipeline so
 * classes without {@code sources.jar} still get a {@code start_line / end_line} attribution
 * (marked {@code virtual_lines = 1} downstream).
 */
public final class VirtualLineAssigner {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualLineAssigner.class);

    private final CfrDecompiler cfr;
    private final MethodMatcher matcher;

    public VirtualLineAssigner() {
        this(new CfrDecompiler(), new MethodMatcher());
    }

    public VirtualLineAssigner(CfrDecompiler cfr, MethodMatcher matcher) {
        this.cfr = cfr;
        this.matcher = matcher;
    }

    public Optional<MethodMatcher.SourceFacts> assign(byte[] classBytes, Fqn owner, MethodKey key) {
        String source = cfr.decompileBytes(classBytes, owner.slashed());
        if (source == null) return Optional.empty();
        Optional<MethodMatcher.SourceFacts> matched = matcher.match(source, owner, key);
        if (matched.isEmpty()) {
            LOG.debug("CFR output did not yield a method match for {}.{}", owner, key);
            return Optional.empty();
        }
        return matched;
    }
}
