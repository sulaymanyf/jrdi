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
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.DubboMethodConfigRepo;
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboRegistryRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DubboXmlPassMethodConfigTest {

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
    void extracts_dubbo_method_configs(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OrderService" ref="orderServiceImpl">
                        <dubbo:method name="findById" timeout="5000" retries="3" loadbalance="random"/>
                        <dubbo:method name="save" timeout="10000" retries="0" async="true"/>
                    </dubbo:service>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        DubboServiceRepo svcRepo = SqliteRepos.dubboServiceRepo(db);
        var services = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.OrderService"));
        assertThat(services).hasSize(1);
        long serviceId = services.get(0).id();

        DubboMethodConfigRepo methodRepo = SqliteRepos.dubboMethodConfigRepo(db);
        List<DubboMethodConfigRepo.Record> methods = methodRepo.findByService(serviceId);
        assertThat(methods).hasSize(2);

        var findById = methodRepo.findByServiceAndMethod(serviceId, "findById").orElseThrow();
        assertThat(findById.timeoutMs()).isEqualTo(5000);
        assertThat(findById.retries()).isEqualTo(3);
        assertThat(findById.loadbalance()).isEqualTo("random");
        assertThat(findById.async()).isFalse();

        var save = methodRepo.findByServiceAndMethod(serviceId, "save").orElseThrow();
        assertThat(save.timeoutMs()).isEqualTo(10000);
        assertThat(save.retries()).isEqualTo(0);
        assertThat(save.async()).isTrue();
    }

    @Test
    void auto_joins_ref_to_impl_class_id_via_spring_beans(@TempDir Path tmp) throws IOException {
        // Step 1: Record a Spring bean in spring_beans for com.acme.OrderServiceImpl
        SpringBeanRepo beanRepo = SqliteRepos.springBeanRepo(db);
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        // We need the class to be indexed first so the auto-join can find the
        // class_id. In a real run, the bytecode pass populates classes; for
        // this test we upsert a fake class row.
        long classId = classRepo.upsert(
                Fqn.fromDotted("com.acme.OrderServiceImpl"),
                0x0001,    // access
                Fqn.fromDotted("java.lang.Object"),
                null, null,
                "jar", java.util.List.of());
        beanRepo.upsert("orderServiceImpl",
                Fqn.fromDotted("com.acme.OrderServiceImpl"),
                "xml", classId, null, "singleton", false);

        // Step 2: Run the Dubbo XML pass; it should auto-join.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OrderService" ref="orderServiceImpl"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        // Verify: impl_class_id is populated with the actual class_id.
        DubboServiceRepo svcRepo = SqliteRepos.dubboServiceRepo(db);
        var services = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.OrderService"));
        assertThat(services).hasSize(1);
        assertThat(services.get(0).implClassId()).isEqualTo(classId);
    }

    @Test
    void unresolvable_ref_leaves_impl_class_id_zero(@TempDir Path tmp) throws IOException {
        // No spring bean is recorded for "ghostImpl".
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.OrderService" ref="ghostImpl"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        var services = SqliteRepos.dubboServiceRepo(db)
                .findByInterface(Fqn.fromDotted("com.acme.api.OrderService"));
        assertThat(services).hasSize(1);
        assertThat(services.get(0).implClassId()).isZero();
        assertThat(services.get(0).refBeanName()).isEqualTo("ghostImpl");
    }

    @Test
    void method_configs_isolated_per_service(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:service interface="com.acme.api.A" ref="aImpl">
                        <dubbo:method name="foo" timeout="1000"/>
                    </dubbo:service>
                    <dubbo:service interface="com.acme.api.B" ref="bImpl">
                        <dubbo:method name="bar" timeout="2000"/>
                    </dubbo:service>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-provider.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        DubboServiceRepo svcRepo = SqliteRepos.dubboServiceRepo(db);
        var aSvc = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.A")).get(0);
        var bSvc = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.B")).get(0);
        var aMethods = SqliteRepos.dubboMethodConfigRepo(db).findByService(aSvc.id());
        var bMethods = SqliteRepos.dubboMethodConfigRepo(db).findByService(bSvc.id());
        assertThat(aMethods).hasSize(1);
        assertThat(aMethods.get(0).methodName()).isEqualTo("foo");
        assertThat(bMethods).hasSize(1);
        assertThat(bMethods.get(0).methodName()).isEqualTo("bar");
    }

    @Test
    void extracts_dubbo_registry_declaration(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:registry id="zkRegistry"
                                    address="zookeeper://10.0.0.1:2181"
                                    protocol="zookeeper"/>
                    <dubbo:registry id="nacosRegistry"
                                    address="nacos://10.0.0.2:8848"
                                    port="8848"
                                    username="nacos"
                                    protocol="nacos">
                        <dubbo:parameter key="namespace" value="public"/>
                    </dubbo:registry>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-config.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        DubboRegistryRepo regRepo = SqliteRepos.dubboRegistryRepo(db);
        var zk = regRepo.findByRegistryId("zkRegistry");
        assertThat(zk).isPresent();
        assertThat(zk.get().address()).isEqualTo("zookeeper://10.0.0.1:2181");
        assertThat(zk.get().protocol()).isEqualTo("zookeeper");
        assertThat(zk.get().sourceFile()).isEqualTo(xmlFile.toString());

        var nacos = regRepo.findByRegistryId("nacosRegistry");
        assertThat(nacos).isPresent();
        assertThat(nacos.get().address()).isEqualTo("nacos://10.0.0.2:8848");
        assertThat(nacos.get().port()).isEqualTo(8848);
        assertThat(nacos.get().username()).isEqualTo("nacos");
        // The <dubbo:parameter> child is captured as a JSON map.
        assertThat(nacos.get().parameters()).contains("\"namespace\":\"public\"");
    }

    @Test
    void service_with_registry_attribute_binds_to_registry(@TempDir Path tmp) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:registry id="zk" address="zookeeper://10.0.0.1:2181"/>
                    <dubbo:registry id="nacos" address="nacos://10.0.0.2:8848"/>
                    <dubbo:service interface="com.acme.api.OrderService" ref="orderImpl" registry="zk"/>
                    <dubbo:service interface="com.acme.api.PayService" ref="payImpl" registry="nacos"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-config.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        // The same service interface declared against two different registries
        // should produce two distinct rows.
        DubboServiceRepo svcRepo = SqliteRepos.dubboServiceRepo(db);
        var orderServices = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.OrderService"));
        assertThat(orderServices).hasSize(1);
        assertThat(orderServices.get(0).registryId()).isEqualTo("zk");

        var payServices = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.PayService"));
        assertThat(payServices).hasSize(1);
        assertThat(payServices.get(0).registryId()).isEqualTo("nacos");
    }

    @Test
    void same_service_targeting_two_registries_yields_two_rows(@TempDir Path tmp) throws IOException {
        // V7 widens the UNIQUE key to include registry_id. This test verifies
        // that the same (interface, group, version, ref, impl) can now appear
        // twice with different registries.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns:dubbo="http://dubbo.apache.org/schema/dubbo">
                    <dubbo:registry id="a" address="zookeeper://a:2181"/>
                    <dubbo:registry id="b" address="zookeeper://b:2181"/>
                    <dubbo:service interface="com.acme.api.S" ref="sImpl" registry="a"/>
                    <dubbo:service interface="com.acme.api.S" ref="sImpl" registry="b"/>
                </beans>
                """;
        Path xmlFile = tmp.resolve("dubbo-config.xml");
        Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);
        new DubboXmlPass(db).scanFile(xmlFile);

        DubboServiceRepo svcRepo = SqliteRepos.dubboServiceRepo(db);
        var services = svcRepo.findByInterface(Fqn.fromDotted("com.acme.api.S"));
        assertThat(services).hasSize(2);
        assertThat(services).extracting(DubboServiceRepo.Record::registryId)
                .containsExactlyInAnyOrder("a", "b");
    }
}
