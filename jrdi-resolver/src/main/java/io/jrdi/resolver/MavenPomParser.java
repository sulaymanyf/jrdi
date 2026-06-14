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
 */
package io.jrdi.resolver;

import io.jrdi.core.coord.Gav;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a Maven project's direct (and optionally transitive)
 * dependencies by reading its {@code pom.xml}. Used by
 * {@code jrdi init} so the operator doesn't have to install
 * Maven and run {@code mvn dependency:tree} — jrdi can answer
 * "what does this project depend on?" from the pom file alone.
 *
 * <p>Implementation: a hand-rolled DOM walk over {@code <dependencies>}
 * and {@code <dependencyManagement>}. We deliberately avoid
 * pulling in the full {@code maven-model} library (300+ KB of
 * transitive dependencies); jrdi is offline-first and that
 * dependency would force the build to fetch it from Central.
 *
 * <p>For the depth-N transitive case we recursively re-parse the
 * dep's own pom from {@code ~/.m2/repository} (which Maven keeps
 * there after the first install). For a depth=1 query the
 * project pom is enough.
 */
public final class MavenPomParser {

    private MavenPomParser() {}

    public record Dependency(String groupId, String artifactId, String version,
                            String scope, boolean optional, String type) {}

    /**
     * Read the {@code <dependencies>} block of a pom and return
     * the direct deps, in declaration order. Versions are
     * resolved against the pom's own {@code <properties>} block
     * AND against the {@code <dependencyManagement>} block of the
     * pom (and recursively, of the parent pom from {@code m2Roots}).
     * Properties in the version field are NOT resolved — that's
     * deferred to {@link #resolveVersionProperty} so we can do it
     * without re-walking the whole tree.
     */
    public static List<Dependency> directDependencies(Path pomFile) throws IOException {
        return directDependencies(pomFile, List.of());
    }

    /**
     * Same as {@link #directDependencies(Path)}, but accepts a
     * hint list of m2 roots for resolving inherited
     * {@code <dependencyManagement>} from parent poms. Pass
     * {@code List.of()} if you don't care about parent BOMs.
     */
    public static List<Dependency> directDependencies(Path pomFile,
                                                       List<Path> m2Roots) throws IOException {
        Document doc = parse(pomFile);
        Element root = doc.getDocumentElement();
        // The pom's <properties> block — for ${...} resolution.
        var properties = collectProperties(root);
        // Flattened dependencyManagement: groupId:artifactId -> version
        var mgmt = collectDependencyManagement(root, pomFile, m2Roots);
        List<Dependency> out = new ArrayList<>();
        // <project><dependencies>
        for (Element deps : childrenByLocalName(root, "dependencies")) {
            for (Element dep : childrenByLocalName(deps, "dependency")) {
                String g = childText(dep, "groupId");
                String a = childText(dep, "artifactId");
                if (g == null || a == null) continue;
                String v = resolveVersionProperty(childText(dep, "version"), properties);
                if (v == null) {
                    v = mgmt.get(g + ":" + a);
                }
                String scope = childText(dep, "scope");
                if (scope == null) scope = "compile";
                String type = childText(dep, "type");
                if (type == null) type = "jar";
                boolean optional = "true".equalsIgnoreCase(childText(dep, "optional"));
                out.add(new Dependency(g, a, v, scope, optional, type));
            }
        }
        return out;
    }

    /**
     * Walk this pom's {@code <dependencyManagement>} and (recursively)
     * the parent pom's {@code <dependencyManagement>} until the chain
     * ends. Parent resolution requires {@code m2Roots} to be non-empty.
     * Also handles {@code <scope>import</scope> <type>pom</type>} BOM
     * imports (used by spring-boot-starter-parent to import
     * spring-boot-dependencies). Returns a map of
     * {@code groupId:artifactId} → resolved version.
     */
    private static java.util.Map<String, String> collectDependencyManagement(
            Element root, Path selfPom, List<Path> m2Roots) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (Element dm : childrenByLocalName(root, "dependencyManagement")) {
            for (Element deps : childrenByLocalName(dm, "dependencies")) {
                for (Element dep : childrenByLocalName(deps, "dependency")) {
                    String g = childText(dep, "groupId");
                    String a = childText(dep, "artifactId");
                    String v = childText(dep, "version");
                    String type = childText(dep, "type");
                    String scope = childText(dep, "scope");
                    if (g == null || a == null || v == null) continue;
                    if (v.startsWith("${")) continue;
                    if ("pom".equals(type) && "import".equals(scope)) {
                        // BOM import: walk the imported pom as if it
                        // were our parent for mgmt purposes.
                        Path bom = findPom(g, a, v, m2Roots);
                        if (bom != null) {
                            try {
                                Document bdoc = parse(bom);
                                var bm = collectDependencyManagement(
                                        bdoc.getDocumentElement(), bom, m2Roots);
                                for (var e : bm.entrySet()) {
                                    out.putIfAbsent(e.getKey(), e.getValue());
                                }
                            } catch (IOException e) {
                                System.err.println("WARN: failed to parse BOM "
                                        + bom + ": " + e.getMessage());
                            }
                        } else {
                            System.err.println("WARN: BOM import not in m2: "
                                    + g + ":" + a + ":" + v);
                        }
                        continue;
                    }
                    out.putIfAbsent(g + ":" + a, v);
                }
            }
        }
        // Walk parent pom. Maven layout:
        //   <groupId> (often in <parent><groupId>) +
        //   <parent><artifactId> +
        //   <parent><version> → m2Roots/<groupPath>/<artifactId>/<version>/<artifactId>-<version>.pom
        if (!m2Roots.isEmpty()) {
            for (Element parent : childrenByLocalName(root, "parent")) {
                String pg = childText(parent, "groupId");
                String pa = childText(parent, "artifactId");
                String pv = childText(parent, "version");
                if (pg == null || pa == null || pv == null) {
                    System.err.println("WARN: parent reference in " + selfPom
                            + " is missing groupId/artifactId/version: "
                            + pg + ":" + pa + ":" + pv);
                    break;
                }
                if (pv.startsWith("${")) {
                    System.err.println("WARN: parent version in " + selfPom
                            + " is unresolved: " + pv
                            + " — BOM resolution limited");
                    break;
                }
                Path found = findPom(pg, pa, pv, m2Roots);
                if (found == null) {
                    String rel = pg.replace('.', '/') + "/" + pa + "/" + pv + "/"
                            + pa + "-" + pv + ".pom";
                    System.err.println("WARN: parent pom not in m2: " + rel
                            + " (searched: " + m2Roots + ")");
                    break;
                }
                try {
                    Document pdoc = parse(found);
                    var pm = collectDependencyManagement(
                            pdoc.getDocumentElement(), found, m2Roots);
                    // Parent entries lose to self.
                    for (var e : pm.entrySet()) {
                        out.putIfAbsent(e.getKey(), e.getValue());
                    }
                } catch (IOException e) {
                    System.err.println("WARN: failed to parse parent pom " + found
                            + ": " + e.getMessage());
                }
                break;  // only one parent
            }
        }
        return out;
    }

    /**
     * Maven layout lookup: {@code <root>/<group-as-path>/<artifact>/<version>/<artifact>-<version>.pom}.
     * Returns the first match across all m2 roots, or null if not found.
     */
    private static Path findPom(String g, String a, String v, List<Path> m2Roots) {
        if (m2Roots.isEmpty()) return null;
        String rel = g.replace('.', '/') + "/" + a + "/" + v + "/"
                + a + "-" + v + ".pom";
        for (Path r : m2Roots) {
            Path p = r.resolve(rel);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * Pull the {@code <groupId>}, {@code <artifactId>}, {@code <version>}
     * of the pom itself (i.e. the project's own GAV).
     */
    public static Gav projectGav(Path pomFile) throws IOException {
        Document doc = parse(pomFile);
        Element root = doc.getDocumentElement();
        String g = childText(root, "groupId");
        String a = childText(root, "artifactId");
        String v = childText(root, "version");
        if (g == null) g = "${project.groupId}";  // inherit from parent
        if (a == null) {
            throw new IOException("pom has no <artifactId>: " + pomFile);
        }
        if (v == null) v = "${project.version}";
        return Gav.of(g, a, v);
    }

    /**
     * Recursively walk the dependency graph up to {@code maxDepth}.
     * Returns the closed set of dependencies (the project's own
     * direct deps plus their transitive deps, deduplicated). The
     * project itself is NOT included in the result.
     *
     * <p>For each dependency, we look in {@code ~/.m2/repository}
     * (and any extra roots passed via {@code m2Roots}) for the
     * dependency's own pom.xml. The first match wins; if a
     * dependency is missing we just skip it (and log a warning).
     */
    public static List<Dependency> resolveGraph(Path projectPom, int maxDepth,
                                                List<Path> m2Roots) throws IOException {
        List<Dependency> direct = directDependencies(projectPom, m2Roots);
        Set<String> seen = new HashSet<>();
        // Seed with the project's own GAV so transitive walks don't loop back.
        Gav selfGav = projectGav(projectPom);
        seen.add(selfGav.toString());

        List<Dependency> out = new ArrayList<>();
        for (Dependency d : direct) {
            if (seen.add(d.groupId() + ":" + d.artifactId() + ":" + d.version())) {
                out.add(d);
            }
        }
        if (maxDepth >= 2) {
            for (Dependency d : List.copyOf(out)) {
                expandTransitive(d, maxDepth - 1, m2Roots, seen, out);
            }
        }
        return out;
    }

    private static void expandTransitive(Dependency dep, int remainingDepth,
                                        List<Path> m2Roots, Set<String> seen,
                                        List<Dependency> out) {
        if (remainingDepth <= 0) return;
        if (!"jar".equals(dep.type()) && !"compile".equals(dep.scope())) {
            // Skip non-jar / provided / test scopes for the transitive
            // walk. We still record the dep itself in `out` because
            // the operator may want to index provided/runtime artifacts
            // explicitly.
            return;
        }
        Path depPom = findDepPom(dep, m2Roots);
        if (depPom == null) return;
        try {
            List<Dependency> next = directDependencies(depPom, m2Roots);
            for (Dependency n : next) {
                String key = n.groupId() + ":" + n.artifactId() + ":" + n.version();
                if (seen.add(key)) {
                    out.add(n);
                    expandTransitive(n, remainingDepth - 1, m2Roots, seen, out);
                }
            }
        } catch (IOException e) {
            // dep's pom is unreadable; skip the subtree.
        }
    }

    private static Path findDepPom(Dependency dep, List<Path> m2Roots) {
        return findPom(dep.groupId(), dep.artifactId(), dep.version(), m2Roots);
    }

    // ─── helpers ───────────────────────────────────────────────────

    private static Document parse(Path pomFile) throws IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            // Some JDKs don't expose these features; the parser
            // will still work, just without XXE hardening.
        }
        try {
            DocumentBuilder builder = dbf.newDocumentBuilder();
            try (InputStream in = Files.newInputStream(pomFile)) {
                return builder.parse(in);
            }
        } catch (Exception e) {
            throw new IOException("failed to parse pom: " + pomFile + " — " + e.getMessage(), e);
        }
    }

    private static List<Element> childrenByLocalName(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if (local.equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName())) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private static String childText(Element parent, String local) {
        for (Element e : childrenByLocalName(parent, local)) {
            return e.getTextContent().trim();
        }
        return null;
    }

    /** Collect <properties>.<x> = y entries into a flat map. */
    private static java.util.Map<String, String> collectProperties(Element root) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (Element props : childrenByLocalName(root, "properties")) {
            NodeList nl = props.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    Element e = (Element) n;
                    String name = e.getLocalName() == null ? e.getTagName() : e.getLocalName();
                    out.put(name, e.getTextContent().trim());
                }
            }
        }
        return out;
    }

    private static String resolveVersionProperty(String raw,
                                                java.util.Map<String, String> properties) {
        if (raw == null) return null;
        if (!raw.startsWith("${") || !raw.endsWith("}")) return raw;
        String key = raw.substring(2, raw.length() - 1);
        // Allow ${foo.bar} chains
        if (key.startsWith("project.")) {
            // The pom parser doesn't know the project's own
            // properties beyond <properties>; ${project.version} and
            // ${project.groupId} come from <project> directly. For
            // now, return the raw form and let the caller resolve.
            return raw;
        }
        String resolved = properties.get(key);
        return resolved != null ? resolved : raw;
    }
}
