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
 */package io.jrdi.classgraph;

import java.util.List;

/**
 * Aggregate output of a {@link ClasspathScanner} run: a flat list of discovered classes.
 * Further analyzers (U3 ASM) will enrich each entry with methods/fields/invokes.
 */
public record ScanReport(List<DiscoveredClass> classes) {

    public int count() {
        return classes.size();
    }
}
