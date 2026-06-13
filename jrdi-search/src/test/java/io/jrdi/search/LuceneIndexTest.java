package io.jrdi.search;

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
