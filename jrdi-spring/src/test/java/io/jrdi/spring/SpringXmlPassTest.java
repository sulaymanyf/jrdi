package io.jrdi.spring;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.SpringBeanRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SpringXmlPassTest {

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
    void parses_bean_declaration(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="ownerService" class="com.acme.OwnerServiceImpl" scope="singleton"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("beans.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

        var r = new SpringXmlPass(db).scanFile(xmlFile);
        assertThat(r.beansRecorded()).isEqualTo(1);
        assertThat(r.filesScanned()).isEqualTo(1);

        SpringBeanRepo repo = SqliteRepos.springBeanRepo(db);
        var beans = repo.findByName("ownerService");
        assertThat(beans).hasSize(1);
        assertThat(beans.get(0).typeFqn()).isEqualTo("com/acme/OwnerServiceImpl");
        assertThat(beans.get(0).source()).isEqualTo("xml");
        assertThat(beans.get(0).scope()).isEqualTo("singleton");
    }

    @Test
    void parses_bean_name_with_comma_separated_aliases(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean name="ownerService,ownerServiceImpl,owner" class="com.acme.OwnerServiceImpl"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("beans.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

        var r = new SpringXmlPass(db).scanFile(xmlFile);
        assertThat(r.beansRecorded()).isEqualTo(1);
        assertThat(r.aliasesRecorded()).isEqualTo(2);  // ownerServiceImpl, owner

        // All three names should resolve to the same type.
        SpringBeanRepo repo = SqliteRepos.springBeanRepo(db);
        for (String name : new String[]{"ownerService", "ownerServiceImpl", "owner"}) {
            var hits = repo.findByName(name);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).typeFqn()).isEqualTo("com/acme/OwnerServiceImpl");
        }
    }

    @Test
    void parses_alias_element(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="realBean" class="com.acme.Foo"/>
                    <alias name="realBean" alias="shortName"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("beans.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new SpringXmlPass(db).scanFile(xmlFile);

        SpringBeanRepo repo = SqliteRepos.springBeanRepo(db);
        // Both realBean and shortName should resolve to the same type.
        assertThat(repo.findByName("realBean")).hasSize(1);
        assertThat(repo.findByName("shortName")).hasSize(1);
        assertThat(repo.findByName("realBean").get(0).typeFqn())
                .isEqualTo(repo.findByName("shortName").get(0).typeFqn());
    }

    @Test
    void detects_component_scan_packages(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:context="http://www.springframework.org/schema/context">
                    <context:component-scan base-package="com.acme.service, com.acme.dao"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("applicationContext.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

        var r = new SpringXmlPass(db).scanFile(xmlFile);
        assertThat(r.componentScanPackages()).isEqualTo(2);
    }

    @Test
    void reads_spring_xml_from_jar(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="alpha" class="com.acme.Alpha"/>
                    <bean id="beta" class="com.acme.Beta"/>
                </beans>
                """;
        Path jar = tmp.resolve("acme-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/spring/applicationContext.xml"));
            z.write(xml.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            // Non-spring XML is ignored
            z.putNextEntry(new ZipEntry("logback.xml"));
            z.write("<configuration/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        var r = new SpringXmlPass(db).scanJar(jar);
        assertThat(r.beansRecorded()).isEqualTo(2);
        assertThat(r.filesScanned()).isEqualTo(1);
    }

    @Test
    void missing_class_attribute_is_skipped(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="orphan"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("beans.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        var r = new SpringXmlPass(db).scanFile(xmlFile);
        assertThat(r.beansRecorded()).isZero();
    }

    @Test
    void primary_attribute_is_preserved(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans">
                    <bean id="primary" class="com.acme.Primary" primary="true"/>
                    <bean id="secondary" class="com.acme.Secondary" primary="false"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("beans.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new SpringXmlPass(db).scanFile(xmlFile);

        SpringBeanRepo repo = SqliteRepos.springBeanRepo(db);
        assertThat(repo.findByName("primary").get(0).primary()).isTrue();
        assertThat(repo.findByName("secondary").get(0).primary()).isFalse();
    }
}
