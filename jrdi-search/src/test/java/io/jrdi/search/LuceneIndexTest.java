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
 */package io.jrdi.search;

import io.jrdi.core.symbol.Fqn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneIndexTest {

    @Test
    void index_and_search(@TempDir Path tmp) throws IOException {
        try (LuceneIndex index = new LuceneIndex(tmp)) {
            index.indexClass(Fqn.fromDotted("com.acme.Greeter"));
            index.indexClass(Fqn.fromDotted("com.acme.Owner"));
            index.indexMethod(Fqn.fromDotted("com.acme.Greeter"), "greet", "(Ljava/lang/String;)V");
            index.commit();

            List<LuceneIndex.Hit> hits = index.search("Greeter", 10);
            assertThat(hits).isNotEmpty();
            assertThat(hits).anyMatch(h -> h.fqn().contains("Greeter"));
        }
    }

    @Test
    void search_does_not_match_absent(@TempDir Path tmp) throws IOException {
        try (LuceneIndex index = new LuceneIndex(tmp)) {
            index.indexClass(Fqn.fromDotted("com.acme.Greeter"));
            index.commit();
            List<LuceneIndex.Hit> hits = index.search("nonexistent_symbol_xyz", 10);
            assertThat(hits).isEmpty();
        }
    }
}
