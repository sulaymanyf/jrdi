package io.jrdi.dubbo;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DubboXmlPassTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.inMemorySqlite();
        Migrator.migrate(db.dataSource());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void parses_dubbo_service_and_reference(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OwnerService" ref="ownerServiceImpl"
                                   group="g1" version="1.0.0" protocol="dubbo"/>
                    <dubbo:reference id="ownerService" interface="com.acme.api.OwnerService"
                                     group="g1" version="1.0.0"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

        DubboXmlPass pass = new DubboXmlPass(db);
        var r = pass.scanFile(xmlFile);

        assertThat(r.servicesRecorded()).isEqualTo(1);
        assertThat(r.referencesRecorded()).isEqualTo(1);
        assertThat(r.filesScanned()).isEqualTo(1);

        DubboServiceRepo sRepo = SqliteRepos.dubboServiceRepo(db);
        var services = sRepo.findByInterface(Fqn.fromDotted("com.acme.api.OwnerService"));
        assertThat(services).hasSize(1);
        assertThat(services.get(0).source()).isEqualTo("xml");
        assertThat(services.get(0).refBeanName()).isEqualTo("ownerServiceImpl");
        assertThat(services.get(0).group()).isEqualTo("g1");
        assertThat(services.get(0).version()).isEqualTo("1.0.0");
        assertThat(services.get(0).protocol()).isEqualTo("dubbo");
        // XML has no class FQN — we record 0 as sentinel.
        assertThat(services.get(0).implClassId()).isZero();

        DubboReferenceRepo rRepo = SqliteRepos.dubboReferenceRepo(db);
        var refs = rRepo.findByInterface(Fqn.fromDotted("com.acme.api.OwnerService"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).refId()).isEqualTo("ownerService");
        assertThat(refs.get(0).fieldId()).isZero();
        assertThat(refs.get(0).confidence()).isEqualTo(DubboReferenceRepo.Confidence.UNCERTAIN);
    }

    @Test
    void reads_xml_from_jar(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OrderService" ref="orderServiceImpl"/>
                </beans>
                """;
        Path jar = tmp.resolve("acme-dubbo-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/spring/dubbo-provider.xml"));
            z.write(xml.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        DubboXmlPass pass = new DubboXmlPass(db);
        var r = pass.scanJar(jar);
        assertThat(r.servicesRecorded()).isEqualTo(1);
        assertThat(r.filesScanned()).isEqualTo(1);
    }

    @Test
    void ignores_non_dubbo_xml(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration>
                    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"/>
                </configuration>
                """;
        Path xmlFile = tmp.resolve("logback.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new DubboXmlPass(db).scanFile(xmlFile);
        assertThat(r.servicesRecorded()).isZero();
        assertThat(r.referencesRecorded()).isZero();
    }

    @Test
    void legacy_alibaba_namespace_works(@TempDir Path tmp) throws IOException {
        // Alibaba 2.6.x: schema is at code.alibabatech.com. Our parser is local-name
        // based so namespace URI doesn't matter.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://code.alibabatech.com/schema/dubbo">
                    <dubbo:service interface="com.legacy.PetService" ref="petImpl"
                                   version="2.0.0" protocol="rmi"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new DubboXmlPass(db).scanFile(xmlFile);
        assertThat(r.servicesRecorded()).isEqualTo(1);
        var services = SqliteRepos.dubboServiceRepo(db)
                .findByInterface(Fqn.fromDotted("com.legacy.PetService"));
        assertThat(services.get(0).protocol()).isEqualTo("rmi");
        assertThat(services.get(0).version()).isEqualTo("2.0.0");
    }

    @Test
    void scan_is_idempotent(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OwnerService" ref="ownerImpl"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        DubboXmlPass pass = new DubboXmlPass(db);
        pass.scanFile(xmlFile);
        pass.scanFile(xmlFile);
        pass.scanFile(xmlFile);
        // V3 ON CONFLICT: only one row survives, even after 3 scans.
        var services = SqliteRepos.dubboServiceRepo(db)
                .findByInterface(Fqn.fromDotted("com.acme.api.OwnerService"));
        assertThat(services).hasSize(1);
    }

    @Test
    void missing_interface_attribute_is_skipped(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service ref="orphanImpl"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new DubboXmlPass(db).scanFile(xmlFile);
        assertThat(r.servicesRecorded()).isZero();
    }

    @Test
    void cross_file_ref_resolves_via_direct_fqn_lookup(@TempDir Path tmp) throws IOException {
        // The ref is a Java FQN, not a Spring bean name. The cross-file
        // resolver should fall through to the classes table when there's no
        // matching spring_beans row.
        //
        // 1. Index the impl class so it's in the classes table.
        long implClassId = SqliteRepos.classRepo(db).upsert(
                Fqn.fromDotted("com.acme.impl.OwnerServiceImpl"),
                1,  // access: public
                Fqn.fromDotted("java.lang.Object"),
                null,  // no file id
                "class OwnerServiceImpl",
                "test");
        // 2. Parse the XML with ref pointing straight at the FQN.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OwnerService"
                                   ref="com.acme.impl.OwnerServiceImpl"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);
        var services = SqliteRepos.dubboServiceRepo(db)
                .findByInterface(Fqn.fromDotted("com.acme.api.OwnerService"));
        assertThat(services).hasSize(1);
        // The FQN-based ref resolved through the classes table, not via a
        // Spring bean lookup.
        assertThat(services.get(0).implClassId()).isEqualTo(implClassId);
        assertThat(services.get(0).refBeanName()).isEqualTo("com.acme.impl.OwnerServiceImpl");
    }
}
