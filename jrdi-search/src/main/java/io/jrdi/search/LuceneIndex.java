package io.jrdi.search;

import io.jrdi.core.symbol.Fqn;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lucene-backed full-text and symbol search. Backed by an FSDirectory on disk so the index
 * survives across CLI invocations.
 *
 * <p>Documents carry the following fields:
 * <ul>
 *   <li>{@code fqn} — class FQN (slashed, stored, indexed as StringField for exact lookup)</li>
 *   <li>{@code name} — simple name (tokenized text for prefix/contains)</li>
 *   <li>{@code kind} — "class" / "method" (StringField)</li>
 *   <li>{@code content} — concatenated signature text (analyzed)</li>
 * </ul>
 */
public final class LuceneIndex implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneIndex.class);

    private final Directory dir;
    private final IndexWriter writer;

    public LuceneIndex(Path indexPath) throws IOException {
        this.dir = FSDirectory.open(indexPath);
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        this.writer = new IndexWriter(dir, config);
    }

    public void indexClass(Fqn fqn) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("fqn", fqn.slashed(), Field.Store.YES));
        // The "name" field uses a WhitespaceTokenizer-style analyzer to keep dotted FQNs
        // searchable token-by-token; for "com.acme.Greeter" the user can query "Greeter"
        // and we find the class via the indexed token.
        doc.add(new TextField("name", fqn.binaryName(), Field.Store.YES));
        doc.add(new StringField("kind", "class", Field.Store.YES));
        // Build a content string with each segment as a separate token for analyzer.
        StringBuilder sb = new StringBuilder();
        for (String part : fqn.dotted().split("\\.")) sb.append(part).append(' ');
        doc.add(new TextField("content", sb.toString(), Field.Store.YES));
        writer.addDocument(doc);
    }

    public void indexMethod(Fqn owner, String methodName, String descriptor) throws IOException {
        Document doc = new Document();
        String fqn = owner.slashed() + "#" + methodName + descriptor;
        doc.add(new StringField("fqn", fqn, Field.Store.YES));
        doc.add(new TextField("name", methodName, Field.Store.YES));
        doc.add(new StringField("kind", "method", Field.Store.YES));
        StringBuilder sb = new StringBuilder();
        for (String part : owner.dotted().split("\\.")) sb.append(part).append(' ');
        sb.append(methodName);
        doc.add(new TextField("content", sb.toString(), Field.Store.YES));
        writer.addDocument(doc);
    }

    public void commit() throws IOException {
        writer.commit();
    }

    public List<Hit> search(String queryString, int limit) throws IOException {
        DirectoryReader reader = DirectoryReader.open(writer);
        IndexSearcher searcher = new IndexSearcher(reader);
        try {
            // Use the QueryParser for the analyzed text field; it handles multi-token queries
            // and is what we want for a "search-like" UX.
            Query query = new QueryParser("content", new StandardAnalyzer()).parse(queryString);
            TopDocs top = searcher.search(query, limit);
            List<Hit> out = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = searcher.storedFields().document(sd.doc);
                Hit h = new Hit(
                        d.get("kind"),
                        d.get("fqn"),
                        Optional.ofNullable(d.get("name")).orElse(""),
                        sd.score);
                out.add(h);
            }
            return out;
        } catch (org.apache.lucene.queryparser.classic.ParseException e) {
            LOG.warn("parse error for {}: {}", queryString, e.getMessage());
            return List.of();
        } finally {
            reader.close();
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
        dir.close();
    }

    public record Hit(String kind, String fqn, String name, float score) {}
}
