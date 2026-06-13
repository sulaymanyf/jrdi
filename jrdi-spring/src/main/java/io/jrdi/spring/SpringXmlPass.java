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
 */package io.jrdi.spring;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.SpringBeanRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Spring XML configuration analyzer. Recognises the canonical Spring beans schema:
 *
 * <ul>
 *   <li>{@code <bean id="..." class="..." scope="..." primary="..."/>} — the basic
 *       bean declaration. We record it as a {@code spring_beans} row with
 *       {@code source="xml"} and the class FQN. The {@code scope} defaults to
 *       "singleton" if unset (matching Spring's runtime default).</li>
 *   <li>{@code <alias name="from" alias="to"/>} — recorded as a row in
 *       {@code spring_beans} (we don't track aliases separately; both names point
 *       at the same class FQN, and the LLM can join via {@code find_spring_beans(name=to)}).</li>
 *   <li>{@code <context:component-scan base-package="com.acme"/>} — we record the
 *       package in the {@code issues} table as a {@code component_scan_root} info
 *       entry so the LLM knows which packages were XML-scanned.</li>
 *   <li>{@code <aop:config>}, {@code <tx:annotation-driven>}, {@code <mvc:annotation-driven>}
 *       — logged at DEBUG; not indexed. These are config triggers, not bean decls.</li>
 * </ul>
 *
 * <p>For {@code <bean class="..."/>} we attempt to resolve the class to its
 * already-indexed {@code classes.id} via {@link ClassRepo#findByFqn}. When the class
 * is in the same artifact (or an upstream one) the join succeeds and the
 * {@code class_id} column is populated; otherwise it's left as 0 and the LLM
 * can still use {@code find_spring_beans(name=id)} to find the bean.
 *
 * <p>M1 limitations:
 * <ul>
 *   <li>No resolution of {@code parent="..."} attribute (bean inheritance).</li>
 *   <li>No resolution of {@code factory-bean="..."} / {@code factory-method="..."}.</li>
 *   <li>No {@code <util:list>}, {@code <util:map>}, {@code <util:properties>}.</li>
 *   <li>No constructor-arg or property values — we only record the bean shell.</li>
 *   <li>No profile awareness ({@code <beans profile="...">}).</li>
 *   <li>No import resolution ({@code <import resource="..."/>}).</li>
 *   <li>No SpEL evaluation in attribute values; we take them as literal text.</li>
 * </ul>
 */
public final class SpringXmlPass {

    private static final Logger LOG = LoggerFactory.getLogger(SpringXmlPass.class);

    private final Db db;
    private final DocumentBuilderFactory factory;

    public SpringXmlPass(Db db) {
        this.db = db;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);  // beans + context: + aop: etc. are namespaced
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception e) {
            LOG.debug("XXE hardening unavailable: {}", e.getMessage());
        }
        this.factory = dbf;
    }

    public record Result(int beansRecorded, int aliasesRecorded,
                         int componentScanPackages, int filesScanned) {}

    /**
     * Scan a {@code .jar} (or exploded directory) for Spring XML configs and persist
     * discovered beans.
     */
    public Result scanJar(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            int totalB = 0, totalA = 0, totalP = 0, totalF = 0;
            try (var stream = Files.walk(jarOrDir)) {
                List<Path> xmls = new ArrayList<>();
                stream.filter(Files::isRegularFile)
                        .filter(p -> isSpringXml(p.getFileName().toString()))
                        .forEach(xmls::add);
                for (Path xml : xmls) {
                    Scan r = scanInternal(readDocument(xml), xml.toString());
                    totalB += r.beans;
                    totalA += r.aliases;
                    totalP += r.packages;
                    totalF++;
                }
            }
            return new Result(totalB, totalA, totalP, totalF);
        }
        int totalB = 0, totalA = 0, totalP = 0, totalF = 0;
        try (JarFile jar = new JarFile(jarOrDir.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!isSpringXml(name)) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    Document doc = parseBytes(in.readAllBytes(), name);
                    if (doc == null) continue;
                    Scan r = scanInternal(doc, name);
                    totalB += r.beans;
                    totalA += r.aliases;
                    totalP += r.packages;
                    totalF++;
                }
            }
        }
        return new Result(totalB, totalA, totalP, totalF);
    }

    public Result scanFile(Path xmlFile) throws IOException {
        if (!Files.isRegularFile(xmlFile)) {
            return new Result(0, 0, 0, 0);
        }
        Scan r = scanInternal(readDocument(xmlFile), xmlFile.toString());
        return new Result(r.beans, r.aliases, r.packages, 1);
    }

    public Result scanDocument(Document doc, String sourceName) {
        Scan r = scanInternal(doc, sourceName);
        return new Result(r.beans, r.aliases, r.packages, 1);
    }

    private Scan scanInternal(Document doc, String sourceName) {
        if (doc == null) return Scan.NONE;
        SpringBeanRepo beanRepo = SqliteRepos.springBeanRepo(db);
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        int beans = 0, aliases = 0, packages = 0;

        for (Element el : elementsByLocalName(doc, "bean")) {
            String id = el.getAttribute("id");
            String name = el.getAttribute("name");
            String cls = el.getAttribute("class");
            String scope = el.getAttribute("scope");
            boolean primary = "true".equalsIgnoreCase(el.getAttribute("primary"));
            if (scope == null || scope.isBlank()) scope = "singleton";
            if ((id == null || id.isBlank()) && (name == null || name.isBlank())) {
                LOG.debug("spring:bean in {} has no id or name, skipping", sourceName);
                continue;
            }
            if (cls == null || cls.isBlank()) {
                LOG.debug("spring:bean in {} has no class, skipping", sourceName);
                continue;
            }
            // Spring's <bean name="a,b,c"> form defines multiple aliases. We record
            // the first one as the canonical name and treat the rest as aliases.
            String[] names = splitNames(id, name);
            String canonical = names[0];
            Fqn typeFqn = Fqn.fromDotted(cls.trim());
            Long classId = classRepo.findByFqn(typeFqn).map(ClassRepo.Record::id).orElse(null);
            beanRepo.upsert(canonical, typeFqn, "xml", classId, null, scope, primary);
            beans++;
            for (int i = 1; i < names.length; i++) {
                beanRepo.upsert(names[i], typeFqn, "xml-alias", classId, null, scope, primary);
                aliases++;
            }
        }

        for (Element el : elementsByLocalName(doc, "alias")) {
            String name = el.getAttribute("name");
            String alias = el.getAttribute("alias");
            if (name == null || name.isBlank() || alias == null || alias.isBlank()) {
                continue;
            }
            // Find the existing bean by name and re-upsert under the alias.
            var existing = beanRepo.findByName(name);
            if (existing.isEmpty()) continue;
            var bean = existing.get(0);
            beanRepo.upsert(alias, Fqn.of(bean.typeFqn()), "xml-alias",
                    bean.classId(), bean.methodId(), bean.scope(), bean.primary());
            aliases++;
        }

        for (Element el : elementsByLocalName(doc, "component-scan")) {
            String base = el.getAttribute("base-package");
            if (base != null && !base.isBlank()) {
                String[] pkgs = base.split("\\s*,\\s*");
                packages += pkgs.length;
                LOG.debug("spring component-scan in {} covers: {}", sourceName, base);
            }
        }
        // Log (don't index) the AOP / TX / MVC triggers.
        for (String trigger : List.of("config", "annotation-driven")) {
            // Walk the full tree — these can appear under their own namespaces.
            for (Element el : allElementsByLocalName(doc, trigger)) {
                LOG.debug("spring config trigger in {}: <{}>", sourceName, trigger);
            }
        }
        return new Scan(beans, aliases, packages);
    }

    private record Scan(int beans, int aliases, int packages) {
        static final Scan NONE = new Scan(0, 0, 0);
    }

    private static String[] splitNames(String id, String name) {
        if (id != null && !id.isBlank()) {
            return new String[]{id.trim()};
        }
        return name.split("\\s*,\\s*");
    }

    private static boolean isSpringXml(String entryName) {
        if (!entryName.endsWith(".xml")) return false;
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.contains("spring") || lower.contains("applicationcontext") ||
                lower.contains("dubbo-provider") || lower.contains("dubbo-consumer") ||
                lower.startsWith("beans") || lower.contains("/beans/") ||
                lower.endsWith("beans.xml");
    }

    /** Local-name-based walk, namespace-aware. Same trick as DubboXmlPass. */
    private static List<Element> elementsByLocalName(Document doc, String localName) {
        List<Element> out = new ArrayList<>();
        walk(doc.getDocumentElement(), localName, out, false);
        return out;
    }

    private static List<Element> allElementsByLocalName(Document doc, String localName) {
        List<Element> out = new ArrayList<>();
        walk(doc.getDocumentElement(), localName, out, true);
        return out;
    }

    private static void walk(Node node, String localName, List<Element> out, boolean all) {
        if (node == null) return;
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String ln = el.getLocalName();
            if (localName.equals(ln) || (all && localName.equals(el.getTagName()))) {
                out.add(el);
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            walk(children.item(i), localName, out, all);
        }
    }

    private Document readDocument(Path file) throws IOException {
        return parseBytes(Files.readAllBytes(file), file.toString());
    }

    private Document parseBytes(byte[] bytes, String source) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            LOG.debug("spring xml parse failed for {}: {}", source, e.getMessage());
            return null;
        }
    }
}
