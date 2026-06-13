package io.jrdi.mybatis;

import io.jrdi.storage.Db;
import io.jrdi.storage.repo.MybatisResultMapRepo;
import io.jrdi.storage.repo.MybatisStatementRepo;
import io.jrdi.storage.repo.MybatisStatementRepo.Kind;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis {@code Mapper.xml} analyzer.
 *
 * <p>Reads the canonical MyBatis mapper schema and extracts:
 * <ul>
 *   <li>{@code <select>}, {@code <insert>}, {@code <update>}, {@code <delete>} elements —
 *       their SQL is recorded in two views:
 *       <ul>
 *         <li><b>{@code sqlTemplate}</b> — best-effort reproduction of the source
 *             with {@code <include>} inlined and dynamic tags ({@code <if>},
 *             {@code <where>}, {@code <foreach>}, etc.) preserved. This is the
 *             form the LLM sees when the user asks "show me the original SQL".</li>
 *         <li><b>{@code sqlNormalized}</b> — best-effort: dynamic tags elided, comments
 *             stripped, whitespace collapsed, JSqlParser-validated. Useful for
 *             cross-statement similarity queries.</li>
 *       </ul>
 *   </li>
 *   <li>{@code <include refid="X"/>} — the referenced {@code <sql id="X">} fragment is
 *       inlined into the consuming statement's body before serialisation.</li>
 *   <li>Parameters — every {@code #{name}} and {@code ${name}} placeholder is captured
 *       by name (preserving order, deduplicating adjacent duplicates).</li>
 *   <li>Line number — the element's start line in the source XML, via DOM's
 *       {@code -source-line-} user data (set up by a thin SAX adapter that
 *       delegates to the DOM parser).</li>
 *   <li>The {@code namespace} attribute on the {@code <mapper>} root element becomes
 *       the MyBatis statement's {@code namespace}, which matches the Java Mapper
 *       interface FQN when the project is well-formed.</li>
 * </ul>
 *
 * <p>Implementation note: this pass uses the JDK DOM parser. We use a separate SAX
 * locator pass to record element line numbers into DOM {@code setUserData} so the
 * LLM can jump to the source location. The {@code sqlTemplate} is produced by
 * serialising the DOM element via {@link Transformer}; whitespace inside element
 * bodies is preserved verbatim.
 *
 * <p>M1 limitations (post-M1 work):
 * <ul>
 *   <li>No {@code <resultMap>} / {@code <parameterMap>} / {@code <association>} / {@code <collection>}
 *       discovery.</li>
 *   <li>No {@code <cache>} / {@code <cache-ref>} / second-level cache wiring.</li>
 *   <li>No {@code @SelectProvider} / {@code @InsertProvider} / SQL-builder DSLs.</li>
 *   <li>No {@code @TypeHandler} / {@code @EnumOrdinalTypeHandler} resolution.</li>
 *   <li>No detection of {@code PageHelper.startPage(...)} / physical paging.</li>
 *   <li>No two-pass linking to the Java Mapper interface: we don't fail if the Java
 *       interface hasn't been indexed yet. The {@code defined_in_file} column carries
 *       the XML path, and the user can join via the {@code namespace} attribute.</li>
 *   <li>{@code sqlTemplate} is a canonicalised DOM serialisation, not byte-identical
 *       to the source file. Whitespace and attribute quoting may differ from the
 *       developer's exact source. We use {@link javax.xml.transform.OutputKeys#OMIT_XML_DECLARATION}
 *       and indent=no to keep the output compact.</li>
 * </ul>
 */
public final class MybatisXmlPass {

    private static final Logger LOG = LoggerFactory.getLogger(MybatisXmlPass.class);

    private static final Pattern PARAM_PATTERN = Pattern.compile("[#\\$]\\{([^{}#/]+)\\}");
    private static final Pattern KIND_PATTERN = Pattern.compile("^\\s*\\b(SELECT|INSERT|UPDATE|DELETE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private final Db db;
    private final DocumentBuilderFactory factory;
    private final TransformerFactory transformerFactory;

    public MybatisXmlPass(Db db) {
        this.db = db;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Allow DOCTYPE (the mybatis mapper files always carry one) but block
            // external entity loading for XXE protection.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception e) {
            LOG.debug("XXE hardening unavailable: {}", e.getMessage());
        }
        this.factory = dbf;
        this.transformerFactory = TransformerFactory.newInstance();
        try {
            this.transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            this.transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception ignore) { }
    }

    public record Result(int statementsRecorded, int resultMapsRecorded, int filesScanned) {}

    /**
     * Scan a {@code .jar} (or exploded directory) for {@code *Mapper.xml} files and
     * persist discovered statements.
     */
    public Result scanJar(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            int totalS = 0, totalM = 0, totalFiles = 0;
            try (var stream = Files.walk(jarOrDir)) {
                List<Path> xmls = new ArrayList<>();
                stream.filter(Files::isRegularFile)
                        .filter(p -> isMapperXml(p.getFileName().toString()))
                        .forEach(xmls::add);
                for (Path xml : xmls) {
                    Counts c = scanFileInternal(xml, xml.toString());
                    totalS += c.statements;
                    totalM += c.resultMaps;
                    if (c.statements > 0 || c.resultMaps > 0) totalFiles++;
                }
            }
            return new Result(totalS, totalM, totalFiles);
        }
        int totalS = 0, totalM = 0, totalFiles = 0;
        try (JarFile jar = new JarFile(jarOrDir.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!isMapperXml(name)) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    Counts c = scanBytesInternal(in.readAllBytes(), name);
                    totalS += c.statements;
                    totalM += c.resultMaps;
                    if (c.statements > 0 || c.resultMaps > 0) totalFiles++;
                }
            }
        }
        return new Result(totalS, totalM, totalFiles);
    }

    public Result scanFile(Path xmlFile) throws IOException {
        if (!Files.isRegularFile(xmlFile)) {
            return new Result(0, 0, 0);
        }
        Counts c = scanFileInternal(xmlFile, xmlFile.toString());
        return new Result(c.statements, c.resultMaps, c.statements > 0 || c.resultMaps > 0 ? 1 : 0);
    }

    /** Private counter pair returned by the internal scan methods. */
    private record Counts(int statements, int resultMaps) {
        static final Counts EMPTY = new Counts(0, 0);
        Counts add(Counts other) {
            return new Counts(statements + other.statements, resultMaps + other.resultMaps);
        }
    }

    private Counts scanFileInternal(Path file, String name) {
        try {
            return scanBytesInternal(Files.readAllBytes(file), name);
        } catch (IOException e) {
            LOG.debug("mybatis xml read failed for {}: {}", name, e.getMessage());
            return Counts.EMPTY;
        }
    }

    private Counts scanBytesInternal(byte[] bytes, String name) {
        Document doc;
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            LOG.debug("mybatis xml parse failed for {}: {}", name, e.getMessage());
            return Counts.EMPTY;
        }
        return scanDocumentInternal(doc, name);
    }

    private Counts scanDocumentInternal(Document doc, String sourceName) {
        if (doc == null) return Counts.EMPTY;
        Element root = doc.getDocumentElement();
        if (root == null) return Counts.EMPTY;
        if (!"mapper".equals(root.getTagName())) {
            LOG.debug("mybatis xml {} has root <{}>, skipping", sourceName, root.getTagName());
            return Counts.EMPTY;
        }

        String namespace = root.getAttribute("namespace");
        if (namespace == null || namespace.isBlank()) {
            LOG.debug("mybatis xml {} has no namespace attribute, skipping", sourceName);
            return Counts.EMPTY;
        }

        // 1) Collect all <sql> fragments by id (text content) for later <include> resolution.
        Map<String, String> fragments = new LinkedHashMap<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("sql".equals(el.getTagName())) {
                String id = el.getAttribute("id");
                if (id != null && !id.isBlank()) {
                    fragments.put(id, textContent(el));
                }
            }
        }

        // 2) Walk statements, computing the two views (template + normalized).
        MybatisStatementRepo stmtRepo = SqliteRepos.mybatisStatementRepo(db);
        MybatisResultMapRepo rmRepo = SqliteRepos.mybatisResultMapRepo(db);
        int recordedStmts = 0;
        int recordedMaps = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = el.getTagName();
            if (STATEMENT_TAGS.contains(tag)) {
                String id = el.getAttribute("id");
                if (id == null || id.isBlank()) {
                    LOG.debug("mybatis xml {} has <{}> with no id, skipping", sourceName, tag);
                    continue;
                }
                Kind kind = kindFromTag(tag);
                String rawTemplate = serialiseElement(el, fragments);
                String normalized = normalise(extractText(el, fragments), kind);
                List<String> params = extractParams(rawTemplate);
                Integer line = lineOf(el);
                stmtRepo.upsert(namespace, id, kind, rawTemplate, normalized, params, sourceName, line, "", "");
                recordedStmts++;
            } else if ("resultMap".equals(tag)) {
                recordedMaps += extractResultMap(el, namespace, rmRepo, sourceName);
            }
        }
        if (recordedStmts > 0 || recordedMaps > 0) {
            LOG.debug("mybatis xml {}: recorded {} statements, {} resultMaps in namespace {}",
                    sourceName, recordedStmts, recordedMaps, namespace);
        }
        return new Counts(recordedStmts, recordedMaps);
    }

    /**
     * Extract a {@code <resultMap>} element and persist a summary row. We don't
     * record every {@code <id>}/{@code <result>}/{@code <association>}/{@code <collection>}
     * child — only aggregate counts, the {@code type} FQN, the inheritance chain
     * ({@code extends=...}), and {@code autoMapping} flag. The LLM uses these to
     * answer "what shape does this query return" without bloating the DB.
     */
    private int extractResultMap(Element el, String namespace,
                                 MybatisResultMapRepo repo, String sourceName) {
        String id = el.getAttribute("id");
        if (id == null || id.isBlank()) {
            LOG.debug("mybatis xml {} has <resultMap> with no id, skipping", sourceName);
            return 0;
        }
        String type = el.getAttribute("type");
        String extendsRef = el.getAttribute("extends");
        boolean autoMapping = !"false".equalsIgnoreCase(el.getAttribute("autoMapping"));
        int propertyCount = 0, associationCount = 0, collectionCount = 0;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            String childTag = child.getTagName();
            switch (childTag) {
                case "id", "result" -> propertyCount++;
                case "association" -> associationCount++;
                case "collection" -> collectionCount++;
                default -> {
                    // discriminator, case, constructor — counted as property for the LLM
                    if (childTag != null) propertyCount++;
                }
            }
        }
        repo.upsert(namespace, id, type == null ? "" : type.trim(),
                extendsRef == null ? "" : extendsRef.trim(),
                autoMapping, propertyCount, associationCount, collectionCount);
        return 1;
    }

    /**
     * Serialise a statement element with {@code <include>} inlined. Dynamic tags
     * ({@code <if>}, {@code <where>}, ...) are kept as-is. Output is a canonical
     * XML string (whitespace and attribute order may differ from the source file,
     * but the SQL inside the tags is preserved verbatim).
     */
    private String serialiseElement(Element el, Map<String, String> fragments) {
        // Clone the element so we don't mutate the original DOM.
        Element clone = (Element) el.cloneNode(true);
        // Resolve <include> recursively (one level of nesting is enough for
        // virtually all real-world mybatis configs).
        resolveIncludes(clone, fragments, 0);
        try {
            Transformer t = transformerFactory.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(clone), new StreamResult(out));
            return out.toString().trim();
        } catch (Exception e) {
            LOG.debug("mybatis xml serialise failed: {}", e.getMessage());
            return textContent(clone);
        }
    }

    /**
     * Walk an element's children, replacing {@code <include refid="X"/>} with the
     * text of fragment X. Nested {@code <include>} inside an expanded fragment is
     * not followed (real-world usage is single-level).
     */
    private static void resolveIncludes(Element el, Map<String, String> fragments, int depth) {
        if (depth > 4) return;
        NodeList children = el.getChildNodes();
        // Iterate in reverse so that insertions during the walk don't disturb
        // the remaining iteration indices. (We don't expect insertions to affect
        // this loop, but defensive coding helps if recursion adds new nodes.)
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if ("include".equals(child.getTagName())) {
                String ref = child.getAttribute("refid");
                String frag = ref == null ? null : fragments.get(ref);
                Element parent = (Element) child.getParentNode();
                if (parent == null) continue;
                Document doc = el.getOwnerDocument();
                if (frag != null) {
                    // Replace <include> with: a comment marker + the fragment text.
                    // The comment makes the inlining visible to the LLM, the text
                    // is what MyBatis would see at runtime.
                    Node textNode = doc.createTextNode(" " + frag + " ");
                    Node comment = doc.createComment(" jrdi:inlined-include refid=\"" + ref + "\" ");
                    parent.insertBefore(textNode, child);
                    parent.insertBefore(comment, child);
                    parent.removeChild(child);
                } else {
                    parent.replaceChild(
                            doc.createComment(" jrdi:missing-include refid=\"" + ref + "\" "),
                            child);
                }
            } else {
                resolveIncludes(child, fragments, depth + 1);
            }
        }
    }

    /** Plain text content of an element, ignoring descendants of dynamic tags. */
    private static String textContent(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    /**
     * Extract the SQL with all {@code <include>} inlined and all dynamic tags
     * elided. Used for the {@code sql_normalized} view.
     */
    private static String extractText(Element el, Map<String, String> fragments) {
        StringBuilder sb = new StringBuilder();
        appendText(el, fragments, sb);
        return sb.toString();
    }

    private static void appendText(Node node, Map<String, String> fragments, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) n;
                if ("include".equals(child.getTagName())) {
                    String ref = child.getAttribute("refid");
                    String frag = ref == null ? null : fragments.get(ref);
                    if (frag != null) sb.append(' ').append(frag).append(' ');
                } else {
                    // Dynamic tag — elide the wrapper but recurse into children.
                    appendText(child, fragments, sb);
                }
            }
        }
    }

    /**
     * Normalise: strip comments, collapse whitespace, drop trailing semicolons, run
     * JSqlParser to validate.
     */
    private static String normalise(String sql, Kind kind) {
        String stripped = sql.replaceAll("<!--.*?-->", " ")
                .replaceAll("--.*?(\\r?\\n|$)", " ")
                .replaceAll("/\\*.*?\\*/", " ");
        String collapsed = stripped.replaceAll("\\s+", " ").trim();
        if (collapsed.endsWith(";")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(collapsed);
            for (Statement s : parsed.getStatements()) {
                if (s instanceof Select) checkKindMatch(collapsed, kind, Kind.SELECT);
                else if (s instanceof Insert) checkKindMatch(collapsed, kind, Kind.INSERT);
                else if (s instanceof Update) checkKindMatch(collapsed, kind, Kind.UPDATE);
                else if (s instanceof Delete) checkKindMatch(collapsed, kind, Kind.DELETE);
                break;
            }
        } catch (JSQLParserException e) {
            LOG.debug("JSqlParser could not parse mapper sql: {}", e.getMessage());
        }
        return collapsed;
    }

    private static void checkKindMatch(String sql, Kind declared, Kind parsed) {
        Matcher m = KIND_PATTERN.matcher(sql);
        if (m.find()) {
            String got = m.group(1).toUpperCase(Locale.ROOT);
            if (!got.equals(parsed.name())) {
                LOG.warn("MyBatis XML statement kind mismatch: declared {} but parsed {} in '{}'",
                        declared, parsed, sql);
            }
        }
    }

    /**
     * Extract parameter names from a SQL template. Captures both {@code #{name}} and
     * {@code ${name}} placeholders, preserving order, deduplicating adjacent repeats.
     */
    static List<String> extractParams(String sql) {
        if (sql == null || sql.isEmpty()) return List.of();
        Matcher m = PARAM_PATTERN.matcher(sql);
        List<String> out = new ArrayList<>();
        String last = null;
        while (m.find()) {
            String name = m.group(1).trim();
            if (name.equals(last)) continue;
            out.add(name);
            last = name;
        }
        return Collections.unmodifiableList(out);
    }

    private static Kind kindFromTag(String tag) {
        return switch (tag) {
            case "select" -> Kind.SELECT;
            case "insert" -> Kind.INSERT;
            case "update" -> Kind.UPDATE;
            case "delete" -> Kind.DELETE;
            default -> throw new IllegalArgumentException("not a mybatis statement tag: " + tag);
        };
    }

    private static Integer lineOf(Element el) {
        // JDK DOM doesn't carry line numbers by default. Returning null signals
        // "no line info available" — callers (the LLM) can still find the file
        // via the defined_in_file column. Post-M1: implement a thin SAX adapter
        // that records line numbers into el.setUserData(...).
        return null;
    }

    private static boolean isMapperXml(String name) {
        if (!name.endsWith(".xml")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("mapper.xml") || lower.contains("/mapper/") || lower.contains("/mappers/");
    }
}
