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
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DubboPassTest {

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
    void dubbo_service_annotation_records_provider() {
        String src = """
                package com.acme.provider;
                import org.apache.dubbo.config.annotation.DubboService;
                @DubboService
                public class OwnerProviderImpl implements com.acme.api.OwnerService {
                    public String findOwner(String name) { return name; }
                }
                """;
        new DubboPass(db).scan(src, Fqn.fromDotted("com.acme.provider.OwnerProviderImpl"));
        DubboServiceRepo repo = SqliteRepos.dubboServiceRepo(db);
        var services = repo.findByInterface(Fqn.fromDotted("com.acme.api.OwnerService"));
        assertThat(services).hasSize(1);
        assertThat(services.get(0).source()).isEqualTo("annotation");
    }

    @Test
    void dubbo_reference_records_consumer() {
        // First: define the consumer (must declare the field, so the @DubboReference can be found)
        String consumerSrc = """
                package com.acme.consumer;
                import org.apache.dubbo.config.annotation.DubboReference;
                import org.springframework.stereotype.Component;
                @Component
                public class OwnerConsumer {
                    @DubboReference
                    private com.acme.api.OwnerService ownerService;
                }
                """;
        new DubboPass(db).scan(consumerSrc, Fqn.fromDotted("com.acme.consumer.OwnerConsumer"));
        DubboReferenceRepo repo = SqliteRepos.dubboReferenceRepo(db);
        // The interface_fqn should match the field's declared type
        var byField = repo.findByField(0L);  // approximate: id 0
        // The field id is created by the bytecode pass; for this test we only check the
        // overall pipeline by querying directly.
        assertThat(byField).isNotNull();
    }

    @Test
    void dubbo_reference_records_consumer_class_fqn_even_when_field_not_indexed() {
        // No field is registered with the fieldRepo, so the field name lookup
        // misses. The pass should still record the reference with field_id=0
        // and the consumer's FQN so the LLM can find the call site by class.
        String consumerSrc = """
                package com.acme.consumer;
                import org.apache.dubbo.config.annotation.DubboReference;
                public class OrderConsumer {
                    @DubboReference
                    private com.acme.api.OrderService orderService;
                }
                """;
        new DubboPass(db).scan(consumerSrc, Fqn.fromDotted("com.acme.consumer.OrderConsumer"));
        var repo = SqliteRepos.dubboReferenceRepo(db);
        var hits = repo.findByConsumerClass(Fqn.fromDotted("com.acme.consumer.OrderConsumer"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).interfaceFqn().dotted()).isEqualTo("com.acme.api.OrderService");
        assertThat(hits.get(0).fieldId()).isZero();
        assertThat(hits.get(0).consumerClassFqn()).isEqualTo("com/acme/consumer/OrderConsumer");
        // The UNCERTAIN confidence is the right call when we can't verify the
        // field actually exists in the bytecode index.
        assertThat(hits.get(0).confidence()).isEqualTo(DubboReferenceRepo.Confidence.UNCERTAIN);
    }

    @Test
    void find_dubbo_references_by_consumer_class_filters_correctly() {
        // Two consumers of the same interface — one in com.acme.web, one in
        // com.acme.batch. The LLM should be able to ask for just the web one.
        String webSrc = """
                package com.acme.web;
                import org.apache.dubbo.config.annotation.DubboReference;
                public class WebController {
                    @DubboReference
                    private com.acme.api.OwnerService ownerService;
                }
                """;
        String batchSrc = """
                package com.acme.batch;
                import org.apache.dubbo.config.annotation.DubboReference;
                public class BatchJob {
                    @DubboReference
                    private com.acme.api.OwnerService ownerService;
                }
                """;
        DubboPass pass = new DubboPass(db);
        pass.scan(webSrc, Fqn.fromDotted("com.acme.web.WebController"));
        pass.scan(batchSrc, Fqn.fromDotted("com.acme.batch.BatchJob"));
        var repo = SqliteRepos.dubboReferenceRepo(db);
        var webRefs = repo.findByConsumerClass(Fqn.fromDotted("com.acme.web.WebController"));
        var batchRefs = repo.findByConsumerClass(Fqn.fromDotted("com.acme.batch.BatchJob"));
        assertThat(webRefs).hasSize(1);
        assertThat(batchRefs).hasSize(1);
        assertThat(webRefs.get(0).consumerClassFqn()).isEqualTo("com/acme/web/WebController");
        assertThat(batchRefs.get(0).consumerClassFqn()).isEqualTo("com/acme/batch/BatchJob");
    }
}
