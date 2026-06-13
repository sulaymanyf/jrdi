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
 */package io.jrdi.dubbo;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.DubboMethodConfigRepo;
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboRegistryRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
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
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * XML configuration analyzer for Apache Dubbo 2.6.x and 3.x.
 *
 * <p>Recognises the canonical Spring-schema entries:
 * <ul>
 *   <li>{@code <dubbo:service interface="X" ref="Y" group="g" version="v" protocol="p"/>} —
 *       provider side. {@code ref} is a Spring bean name; the pass auto-joins this
 *       with {@code spring_beans} (and then {@code classes}) to fill in
 *       {@code impl_class_id}. If the bean isn't yet indexed, the LLM still sees
 *       the raw {@code ref_bean_name} and can join manually.</li>
 *   <li>{@code <dubbo:reference id="Y" interface="X" group="g" version="v"/>} — consumer
 *       side. {@code id} is the bean name the framework will inject into the consumer
 *       field by name.</li>
 *   <li>{@code <dubbo:method name="foo" timeout="5000" retries="3" loadbalance="random"
 *       async="true" sent="true"/>} — per-method config, nested inside either
 *       {@code <dubbo:service>} or {@code <dubbo:reference>}. Recorded into
 *       {@code dubbo_method_configs} with a foreign key to the parent row.</li>
 *   <li>Both forms are accepted as self-closing ({@code <dubbo:service .../>}) and
 *       as open/close pairs (e.g. with nested {@code <dubbo:method/>} children).</li>
 * </ul>
 *
 * <p>Namespace support: the parser is namespace-aware. The Dubbo schema URI
 * ({@code http://dubbo.apache.org/schema/dubbo}) is the primary target. Spring's
 * {@code <beans>} wrapper is required and its namespace must be on the root element.
 *
 * <p>For Spring-MVC integration, the common pattern {@code <dubbo:annotation/>} is NOT
 * scanned — that's a configuration trigger, not a service definition.
 *
 * <p>Storage:
 * <ul>
 *   <li>{@code dubbo_services(interface_fqn, impl_class_id, ref_bean_name, group, version, protocol, source="xml")}</li>
 *   <li>{@code dubbo_references(interface_fqn, field_id=0, ref_id, group, version, confidence=UNCERTAIN)}</li>
 *   <li>{@code dubbo_method_configs(service_id, method_name, timeout_ms, retries, loadbalance, async, sent)}</li>
 * </ul>
 *
 * <p>M1 limitations:
 * <ul>
 *   <li>No schema validation (we use a non-validating parser). This means typo'd
 *       attribute names will be silently ignored.</li>
 *   <li>The auto-join {@code ref → impl_class_id} only succeeds when the Spring bean
 *       has already been recorded in {@code spring_beans} (which happens in pass-2
 *       of the pipeline, before this pass). For multi-artifact projects where the
 *       bean lives in a different jar, the join may be missed on the first run;
 *       re-indexing fixes it.</li>
 *   <li>Generic {@code <dubbo:service interface="com.acme.GenericService"/>} is
 *       recorded as {@code interface_fqn=com.acme.GenericService}. Runtime method
 *       resolution is out of scope (no agent).</li>
 *   <li>No {@code <dubbo:service interface="X" ref="ref" registry="N"/>} registry
 *       attribute is captured.</li>
 *   <li>No service group/version inheritance via {@code <dubbo:provider/>} defaults.</li>
 *   <li>No annotation + XML conflict detection (we record both).</li>
 * </ul>
 */
public final class DubboXmlPass {

    private static final Logger LOG = LoggerFactory.getLogger(DubboXmlPass.class);

    private final Db db;
    private final DocumentBuilderFactory factory;

    public DubboXmlPass(Db db) {
        this.db = db;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
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

    public record Result(int servicesRecorded, int referencesRecorded,
                         int methodConfigsRecorded, int filesScanned,
                         int registriesRecorded) {}

    public Result scanJar(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            int totalS = 0, totalR = 0, totalM = 0, totalF = 0, totalReg = 0;
            for (String rel : DUBBO_XML_PATTERNS) {
                Path p = jarOrDir.resolve(rel);
                if (!Files.isRegularFile(p)) continue;
                Scan r = scanInternal(readDocument(p), rel);
                totalS += r.services;
                totalR += r.references;
                totalM += r.methodConfigs;
                totalReg += r.registries;
                totalF++;
            }
            return new Result(totalS, totalR, totalM, totalF, totalReg);
        }
        int totalS = 0, totalR = 0, totalM = 0, totalF = 0, totalReg = 0;
        try (JarFile jar = new JarFile(jarOrDir.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!isDubboXml(name)) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    Document doc = parseBytes(in.readAllBytes(), name);
                    if (doc == null) continue;
                    Scan r = scanInternal(doc, name);
                    totalS += r.services;
                    totalR += r.references;
                    totalM += r.methodConfigs;
                    totalReg += r.registries;
                    totalF++;
                }
            }
        }
        return new Result(totalS, totalR, totalM, totalF, totalReg);
    }

    public Result scanFile(Path xmlFile) throws IOException {
        if (!Files.isRegularFile(xmlFile)) {
            return new Result(0, 0, 0, 0, 0);
        }
        Scan r = scanInternal(readDocument(xmlFile), xmlFile.toString());
        return new Result(r.services, r.references, r.methodConfigs, 1, r.registries);
    }

    public Result scanDocument(Document doc, String sourceName) {
        Scan r = scanInternal(doc, sourceName);
        return new Result(r.services, r.references, r.methodConfigs, 1, r.registries);
    }

    private Scan scanInternal(Document doc, String sourceName) {
        if (doc == null) return Scan.NONE;
        DubboServiceRepo serviceRepo = SqliteRepos.dubboServiceRepo(db);
        DubboReferenceRepo refRepo = SqliteRepos.dubboReferenceRepo(db);
        DubboMethodConfigRepo methodRepo = SqliteRepos.dubboMethodConfigRepo(db);
        DubboRegistryRepo registryRepo = SqliteRepos.dubboRegistryRepo(db);
        SpringBeanRepo beanRepo = SqliteRepos.springBeanRepo(db);
        ClassRepo classRepo = SqliteRepos.classRepo(db);

        int services = 0, references = 0, methodConfigs = 0, registries = 0;
        
        // First pass: <dubbo:registry> declarations. These are top-level
        // (not nested inside a service/reference) and are the keys the
        // service/reference-level "registry" attribute refers to.
        for (Element el : elementsByLocalName(doc, "registry")) {
            String id = el.getAttribute("id");
            if (id == null || id.isBlank()) {
                LOG.debug("dubbo:registry in {} missing id attribute", sourceName);
                continue;
            }
            String address = el.getAttribute("address");
            String protocol = el.getAttribute("protocol");
            Integer port = parseIntOrNull(el.getAttribute("port"));
            String username = el.getAttribute("username");
            StringBuilder params = new StringBuilder("{");
            NodeList children = el.getChildNodes();
            boolean first = true;
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE
                        && "parameter".equals(((Element) n).getLocalName())) {
                    Element p = (Element) n;
                    if (!first) params.append(',');
                    params.append('"').append(p.getAttribute("key").replace("\"", "\\\""))
                            .append("\":\"").append(p.getAttribute("value").replace("\"", "\\\""))
                            .append('"');
                    first = false;
                }
            }
            params.append('}');
            registryRepo.upsert(id, address, protocol, port, username,
                    params.toString(), sourceName);
            registries++;
        }
        for (Element el : elementsByLocalName(doc, "service")) {
            String iface = el.getAttribute("interface");
            if (iface == null || iface.isBlank()) {
                LOG.debug("dubbo:service in {} missing interface attribute", sourceName);
                continue;
            }
            Fqn ifaceFqn = Fqn.fromDotted(iface.trim());
            String ref = el.getAttribute("ref");
            String group = el.getAttribute("group");
            String version = el.getAttribute("version");
            String protocol = el.getAttribute("protocol");
            if (protocol.isBlank()) protocol = "dubbo";
            long implClassId = resolveImplClassId(ref, beanRepo, classRepo);
            long serviceId = serviceRepo.upsert(ifaceFqn, implClassId, group, version, protocol,
                    "xml", ref == null ? "" : ref, el.getAttribute("registry"));
            services++;
            methodConfigs += extractMethodConfigs(el, methodRepo, serviceId, 0L);
        }
        for (Element el : elementsByLocalName(doc, "reference")) {
            String iface = el.getAttribute("interface");
            if (iface == null || iface.isBlank()) {
                LOG.debug("dubbo:reference in {} missing interface attribute", sourceName);
                continue;
            }
            Fqn ifaceFqn = Fqn.fromDotted(iface.trim());
            String id = el.getAttribute("id");
            String group = el.getAttribute("group");
            String version = el.getAttribute("version");
            String registryAttr = el.getAttribute("registry");
            refRepo.record(ifaceFqn, 0L, group, version,
                    DubboReferenceRepo.Confidence.UNCERTAIN,
                    id == null ? "" : id, registryAttr == null ? "" : registryAttr,
                    /* consumerClassFqn */ "");
            references++;
            // The reference's id in the DB is the auto-generated row id; we
            // extract method configs with reference_id pointing at the newly
            // inserted row. We don't have the id back from the upsert (it's
            // a void method), so for the rare consumer-side <dubbo:method/>
            // case we do an additional lookup. For typical configs (provider-side
            // timeouts/retries) the serviceId path covers the common case.
            //
            // We can still record method configs keyed by the (interface, group,
            // version, ref_id) tuple even without the row id, by issuing a
            // targeted upsert with reference_id from a follow-up SELECT.
            long refRowId = findReferenceId(ifaceFqn, group, version, id);
            methodConfigs += extractMethodConfigs(el, methodRepo, 0L, refRowId);
        }
        LOG.debug("dubbo xml scan: {} (services={}, references={}, methods={})",
                sourceName, services, references, methodConfigs);
        return new Scan(services, references, methodConfigs, registries);
    }

    /**
     * Auto-join {@code ref_bean_name} to a real {@code classes.id}:
     * <ol>
     *   <li>If {@code ref} matches a {@code spring_beans.name} (typical case
     *       when XML uses {@code ref="myBean"}), use the bean's type FQN to
     *       look up the class.</li>
     *   <li>Otherwise, if {@code ref} looks like a Java FQN (contains a dot,
     *       ends with uppercase-style name), treat it as a direct class
     *       reference and look it up in {@code classes} directly. This covers
     *       the cross-file case where the bean is declared in a different
     *       Spring XML file (or via {@code @Bean} in a {@code @Configuration}
     *       class) — the ref points straight at the impl class.</li>
     *   <li>Returns 0 when neither chain resolves (the impl class hasn't been
     *       indexed yet; a re-index run will usually fix it).</li>
     * </ol>
     */
    private long resolveImplClassId(String ref, SpringBeanRepo beanRepo, ClassRepo classRepo) {
        if (ref == null || ref.isBlank()) return 0L;
        // 1) Spring bean name lookup (covers the same-file case).
        var beans = beanRepo.findByName(ref);
        if (!beans.isEmpty()) {
            Fqn typeFqn = Fqn.of(beans.get(0).typeFqn());
            Optional<ClassRepo.Record> cls = classRepo.findByFqn(typeFqn);
            if (cls.isPresent()) return cls.get().id();
        }
        // 2) Direct FQN lookup. Spring's <ref bean="..."/> can also accept a
        // class FQN in <dubbo:service ref="..."/>, and many real-world configs
        // use this form to bypass the bean-name layer. Also covers cross-file
        // refs that don't have a matching local bean.
        if (ref.indexOf('.') >= 0) {
            Fqn refFqn = Fqn.fromDotted(ref.trim());
            Optional<ClassRepo.Record> cls = classRepo.findByFqn(refFqn);
            if (cls.isPresent()) return cls.get().id();
        }
        return 0L;
    }

    /**
     * Look up the {@code dubbo_references} row we just inserted. We do a single
     * SELECT by the natural key. Returns 0 if the row can't be found (e.g. due to
     * a foreign-key violation; the consumer-side method config will still be
     * persisted as a no-op row).
     */
    private long findReferenceId(Fqn ifaceFqn, String group, String version, String refId) {
        if (refId == null || refId.isBlank()) return 0L;
        var refs = SqliteRepos.dubboReferenceRepo(db).findByInterface(ifaceFqn);
        for (var r : refs) {
            if (refId.equals(r.refId()) &&
                    (group == null ? "" : group).equals(r.group()) &&
                    (version == null ? "" : version).equals(r.version())) {
                return r.id();
            }
        }
        return 0L;
    }

    /**
     * Walk all {@code <dubbo:method>} children of the given parent element and
     * upsert each into {@code dubbo_method_configs}. Returns the count recorded.
     */
    private int extractMethodConfigs(Element parent, DubboMethodConfigRepo repo,
                                     long serviceId, long referenceId) {
        if (serviceId == 0L && referenceId == 0L) {
            return 0;  // No parent to attach to.
        }
        int count = 0;
        for (Element method : elementsByLocalName(parent, "method")) {
            String name = method.getAttribute("name");
            if (name == null || name.isBlank()) continue;
            Integer timeout = parseIntOrNull(method.getAttribute("timeout"));
            Integer retries = parseIntOrNull(method.getAttribute("retries"));
            String loadbalance = blankToNull(method.getAttribute("loadbalance"));
            boolean async = "true".equalsIgnoreCase(method.getAttribute("async"));
            boolean sent = "true".equalsIgnoreCase(method.getAttribute("sent"));
            repo.upsert(serviceId, referenceId, name, timeout, retries, loadbalance, async, sent);
            count++;
        }
        return count;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private record Scan(int services, int references, int methodConfigs, int registries) {
        static final Scan NONE = new Scan(0, 0, 0, 0);
    }

    private static final List<String> DUBBO_XML_PATTERNS = List.of(
            "META-INF/spring/dubbo-provider.xml",
            "META-INF/spring/dubbo-consumer.xml",
            "META-INF/dubbo/dubbo-provider.xml",
            "META-INF/dubbo/dubbo-consumer.xml",
            "dubbo-provider.xml",
            "dubbo-consumer.xml"
    );

    private static boolean isDubboXml(String entryName) {
        if (!entryName.endsWith(".xml")) return false;
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.contains("dubbo") || DUBBO_XML_PATTERNS.contains(entryName);
    }

    private static List<Element> elementsByLocalName(Document doc, String localName) {
        List<Element> out = new ArrayList<>();
        walk(doc.getDocumentElement(), localName, out);
        return out;
    }

    /** Walk children of a given element (not the whole document). */
    private static List<Element> elementsByLocalName(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (localName.equals(el.getLocalName())) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    private static void walk(Node node, String localName, List<Element> out) {
        if (node == null) return;
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String ln = el.getLocalName();
            if (localName.equals(ln)) {
                out.add(el);
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            walk(children.item(i), localName, out);
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
            LOG.debug("dubbo xml parse failed for {}: {}", source, e.getMessage());
            return null;
        }
    }
}
