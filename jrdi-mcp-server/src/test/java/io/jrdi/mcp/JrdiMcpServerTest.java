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
 */package io.jrdi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JrdiMcpServerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void subscribe_and_publish_yields_notification_notification(@TempDir Path tmp) throws Exception {
        // Verify the full MCP subscription flow:
        // 1. Client subscribes to jrdi://stats
        // 2. Server returns OK
        // 3. Client reads jrdi://stats → triggers a publish
        // 4. Server writes a resources/updated notification to the stream
        // 5. Unsubscribe stops notifications
        JrdiMcpServer server = new JrdiMcpServer(
                JrdiMcpService.openSqlite("jdbc:sqlite:" + tmp.resolve("sub.db")));
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"resources/subscribe","params":{"uri":"jrdi://stats"}}
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"jrdi://stats"}}
                {"jsonrpc":"2.0","id":4,"method":"resources/unsubscribe","params":{"uri":"jrdi://stats"}}
                {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"jrdi://stats"}}
                """.trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> lines = readAllLines(out);

        // 1. initialize OK
        assertThat(lines.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(lines.get(0).get("result").get("capabilities").get("resources").get("subscribe").asBoolean())
                .isTrue();

        // 2. subscribe OK with subscriptionId. The exact line index depends
        // on whether the prior read fired a notification, so we scan by id.
        JsonNode subResp = findResponseWithId(lines, 2);
        assertThat(subResp).as("subscribe response with id=2").isNotNull();
        assertThat(subResp.get("result").has("subscriptionId")).isTrue();
        int subId = subResp.get("result").get("subscriptionId").asInt();
        assertThat(subId).isNotZero();

        // 3. resources/read on jrdi://stats → triggers a publish → notification
        // We expect 2 lines after read #1: the read response AND the
        // notification (which is a notification, no id, no result).
        // Find the notification among the captured lines.
        int afterFirstRead = 4;  // lines: init, sub, read, NOTIFICATION, read2
        // Actually due to the bus.publish() call in onResourceRead, the
        // notification is interleaved. The simplest check: there should be
        // exactly one resources/updated notification, between the two
        // resources/read responses.
        boolean foundNotification = false;
        int notifications = 0;
        for (int i = 2; i < lines.size(); i++) {
            JsonNode line = lines.get(i);
            if (line.has("method") && "resources/updated".equals(line.get("method").asText())) {
                notifications++;
                assertThat(line.get("params").get("uri").asText()).isEqualTo("jrdi://stats");
                assertThat(line.has("id")).isFalse();  // notification, no id
                foundNotification = true;
            }
        }
        assertThat(foundNotification).as("expected at least one resources/updated notification").isTrue();

        // 4. unsubscribe OK
        JsonNode unsubResp = findResponseWithId(lines, 4);
        assertThat(unsubResp).as("unsubscribe response with id=4").isNotNull();
        assertThat(unsubResp.get("result")).isNotNull();

        // 5. After unsubscribe, the second read should NOT trigger a
        // notification. We verify by counting: exactly one notification
        // total (from the first read), not two.
        assertThat(notifications).as("expected exactly one notification (post-unsubscribe read is silent)")
                .isEqualTo(1);
    }

    @Test
    void subscribe_to_unknown_resource_returns_error(@TempDir Path tmp) throws Exception {
        JrdiMcpServer server = new JrdiMcpServer(
                JrdiMcpService.openSqlite("jdbc:sqlite:" + tmp.resolve("unk.db")));
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"resources/subscribe","params":{"uri":"jrdi://nonexistent"}}
                """.trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> responses = readResponses(out);
        assertThat(responses).hasSize(2);
        assertThat(responses.get(1).get("error")).isNotNull();
        assertThat(responses.get(1).get("error").get("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void resource_bus_unit_lifecycle() {
        // Direct test of the bus semantics: subscribe/unsubscribe/publish.
        var bus = new ResourceBus();
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var sub = bus.subscribe("jrdi://stats", uri -> received.incrementAndGet());
        assertThat(bus.count("jrdi://stats")).isEqualTo(1);
        bus.publish("jrdi://stats");
        assertThat(received.get()).isEqualTo(1);
        // Publish to a URI with no subscribers is a no-op.
        bus.publish("jrdi://nothing");
        assertThat(received.get()).isEqualTo(1);
        // Unsubscribe.
        bus.unsubscribe(sub);
        assertThat(bus.count("jrdi://stats")).isZero();
        bus.publish("jrdi://stats");
        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    void initialize_then_tools_list_then_index_status_via_stdio() throws Exception {
        JrdiMcpServer server = new JrdiMcpServer(
                JrdiMcpService.openSqlite("jdbc:sqlite::memory:"));
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"index_status","arguments":{}}}
                """.trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> responses = readResponses(out);
        assertThat(responses).hasSize(3);

        JsonNode r1 = responses.get(0);
        assertThat(r1.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(r1.get("id").asInt()).isEqualTo(1);
        assertThat(r1.get("result").get("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(r1.get("result").get("serverInfo").get("name").asText()).isEqualTo("jrdi");
        // Capabilities: tools + resources + prompts
        assertThat(r1.get("result").get("capabilities").has("tools")).isTrue();
        assertThat(r1.get("result").get("capabilities").has("resources")).isTrue();
        assertThat(r1.get("result").get("capabilities").has("prompts")).isTrue();

        JsonNode r2 = responses.get(1);
        assertThat(r2.get("result").get("tools").size()).isEqualTo(17);

        JsonNode r3 = responses.get(2);
        String text = r3.get("result").get("content").get(0).get("text").asText();
        assertThat(text).contains("\"classes\":0");
        assertThat(text).contains("\"dialect\":\"sqlite\"");
    }

    @Test
    void unknown_tool_after_initialize_returns_error() throws Exception {
        JrdiMcpServer server = new JrdiMcpServer(
                JrdiMcpService.openSqlite("jdbc:sqlite::memory:"));
        String input = """
                {"jsonrpc":"2.0","id":99,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":100,"method":"tools/call","params":{"name":"not_a_real_tool","arguments":{}}}
                """.trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> responses = readResponses(out);
        assertThat(responses).hasSize(2);

        assertThat(responses.get(0).get("result").get("serverInfo").get("name").asText()).isEqualTo("jrdi");
        JsonNode err = responses.get(1).get("error");
        assertThat(err).isNotNull();
        assertThat(err.get("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void resources_and_prompts_are_advertised(@TempDir Path tmp) throws Exception {
        JrdiMcpServer server = new JrdiMcpServer(
                JrdiMcpService.openSqlite("jdbc:sqlite:" + tmp.resolve("x.db")));
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"resources/list","params":{}}
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"jrdi://schema"}}
                {"jsonrpc":"2.0","id":4,"method":"prompts/list","params":{}}
                {"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"find_callers","arguments":{"owner":"com.acme.Foo","name":"bar","desc":"()V"}}}
                """.trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> responses = readResponses(out);
        assertThat(responses).hasSize(5);

        // resources/list → 2 resources (schema + stats)
        JsonNode rl = responses.get(1).get("result").get("resources");
        assertThat(rl.size()).isEqualTo(2);
        assertThat(rl.get(0).get("uri").asText()).isEqualTo("jrdi://schema");
        assertThat(rl.get(1).get("uri").asText()).isEqualTo("jrdi://stats");

        // resources/read jrdi://schema → text contains "version" and "tables"
        String schemaText = responses.get(2).get("result").get("contents").get(0).get("text").asText();
        assertThat(schemaText).contains("\"tables\"");
        assertThat(schemaText).contains("\"version\"");
        assertThat(schemaText).contains("\"spring_beans\"");

        // prompts/list → 6 prompts
        JsonNode pl = responses.get(3).get("result").get("prompts");
        assertThat(pl.size()).isEqualTo(6);
        assertThat(pl.get(0).get("name").asText()).isEqualTo("find_callers");
        assertThat(pl.get(1).get("name").asText()).isEqualTo("find_bean_wiring");
        assertThat(pl.get(2).get("name").asText()).isEqualTo("find_path");
        assertThat(pl.get(3).get("name").asText()).isEqualTo("find_dubbo_method_tuning");
        assertThat(pl.get(4).get("name").asText()).isEqualTo("find_mybatis_query_shape");
        assertThat(pl.get(5).get("name").asText()).isEqualTo("find_rpc_call_chain");

        // prompts/get find_callers → user message with the rendered template
        String promptText = responses.get(4).get("result").get("messages").get(0)
                .get("content").get("text").asText();
        assertThat(promptText).contains("com.acme.Foo");
        assertThat(promptText).contains("callers_of");
    }

    @Test
    void integration_prompts_chain_the_new_framework_tools() throws Exception {
        // The 3 P2.7+ integration prompts must reference the new tool names
        // (find_dubbo_method_configs, find_mybatis_result_maps) and produce
        // multi-step instructions the LLM can follow.
        var service = JrdiMcpService.openSqlite("jdbc:sqlite::memory:");
        JrdiMcpServer server = new JrdiMcpServer(service);
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"find_dubbo_method_tuning","arguments":{"interface":"com.acme.api.OrderApi"}}}
                {"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"find_mybatis_query_shape","arguments":{"namespace":"com.acme.OrderMapper"}}}
                {"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"find_rpc_call_chain","arguments":{"interface":"com.acme.api.OrderApi"}}}
                """.trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> responses = readResponses(out);
        assertThat(responses).hasSize(4);

        // Prompt 1: find_dubbo_method_tuning chains find_dubbo_services + find_dubbo_method_configs
        String tune = responses.get(1).get("result").get("messages").get(0)
                .get("content").get("text").asText();
        assertThat(tune).contains("com.acme.api.OrderApi");
        assertThat(tune).contains("find_dubbo_services");
        assertThat(tune).contains("find_dubbo_method_configs");
        assertThat(tune).contains("timeout");
        assertThat(tune).contains("async");

        // Prompt 2: find_mybatis_query_shape chains find_mybatis_statements + find_mybatis_result_maps
        String shape = responses.get(2).get("result").get("messages").get(0)
                .get("content").get("text").asText();
        assertThat(shape).contains("com.acme.OrderMapper");
        assertThat(shape).contains("find_mybatis_statements");
        assertThat(shape).contains("find_mybatis_result_maps");
        assertThat(shape).contains("resultMap");
        assertThat(shape).contains("N+1");

        // Prompt 3: find_rpc_call_chain is a 3-tool chain
        String chain = responses.get(3).get("result").get("messages").get(0)
                .get("content").get("text").asText();
        assertThat(chain).contains("com.acme.api.OrderApi");
        assertThat(chain).contains("find_dubbo_services");
        assertThat(chain).contains("find_dubbo_references");
        assertThat(chain).contains("find_dubbo_method_configs");
        assertThat(chain).contains("providers:");
        assertThat(chain).contains("consumers:");
        assertThat(chain).contains("PROBABLE");
        assertThat(chain).contains("UNCERTAIN");
    }

    @Test
    void framework_tools_query_real_records() throws Exception {
        // Open an in-memory DB and seed a tiny project: one class, one Spring bean,
        // one Dubbo service, one MyBatis statement, plus the P2.7+ extras
        // (method configs, result map).
        var service = JrdiMcpService.openSqlite("jdbc:sqlite::memory:");
        // Seed via the storage layer.
        var db = service.db();
        var classRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.classRepo(db);
        var methodRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.methodRepo(db);
        var fieldRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.fieldRepo(db);
        var springBeanRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.springBeanRepo(db);
        var dubboServiceRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.dubboServiceRepo(db);
        var mybatisRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.mybatisStatementRepo(db);
        var methodConfigRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.dubboMethodConfigRepo(db);
        var resultMapRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.mybatisResultMapRepo(db);
        var autoconfigRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.springBootAutoconfigRepo(db);

        long classId = classRepo.upsert(io.jrdi.core.symbol.Fqn.fromDotted("com.acme.demo.OrderService"),
                0x0001, null, null, null, "jar", List.of());
        methodRepo.upsert(classId, "save", "()V", null, 10, 20, false);
        long fieldId = fieldRepo.upsert(classId, "orderRepo", "Lcom/acme/OrderRepo;", null, 5);
        springBeanRepo.upsert("orderService", io.jrdi.core.symbol.Fqn.fromDotted("com.acme.demo.OrderService"),
                "annotation", classId, null, "singleton", false);
        long svcId = dubboServiceRepo.upsert(io.jrdi.core.symbol.Fqn.fromDotted("com.acme.api.OrderApi"),
                classId, "g1", "1.0.0", "dubbo", "annotation", "", "");
        mybatisRepo.upsert("com.acme.OrderMapper", "selectById",
                io.jrdi.storage.repo.MybatisStatementRepo.Kind.SELECT,
                "select * from orders where id = #{id}",
                "SELECT * FROM orders WHERE id = ?",
                List.of("id"), "OrderMapper.java", 7, "", "");
        // P2.7+ extras
        methodConfigRepo.upsert(svcId, 0L, "save",
                5000, 3, "random", false, false);
        resultMapRepo.upsert("com.acme.OrderMapper", "orderResult", "com.acme.Order",
                "baseResult", true, 5, 1, 0);
        // V5 Spring Boot autoconfig
        autoconfigRepo.upsert("com.acme.demo.MyAutoConfig",
                "META-INF/spring.factories", "factories",
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration");
        // Reference the fieldId to silence unused-warning
        assertThat(fieldId).isPositive();

        JrdiMcpServer server = new JrdiMcpServer(service);
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_spring_beans","arguments":{}}}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_dubbo_services","arguments":{}}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_mybatis_statements","arguments":{"namespace":"com.acme.OrderMapper"}}}
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find_dubbo_method_configs","arguments":{"serviceId":%d}}}
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"find_mybatis_result_maps","arguments":{"namespace":"com.acme.OrderMapper"}}}
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"find_spring_autoconfigs","arguments":{"class":"com.acme.demo.MyAutoConfig"}}}
                """.formatted(svcId).trim() + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        List<JsonNode> responses = readResponses(out);
        assertThat(responses).hasSize(7);

        // Spring: 1 bean named orderService
        String spring = responses.get(1).get("result").get("content").get(0).get("text").asText();
        assertThat(spring).contains("\"count\":1");
        assertThat(spring).contains("orderService");
        assertThat(spring).contains("com/acme/demo/OrderService");

        // Dubbo: 1 service
        String dubbo = responses.get(2).get("result").get("content").get(0).get("text").asText();
        assertThat(dubbo).contains("\"count\":1");
        assertThat(dubbo).contains("com/acme/api/OrderApi");
        assertThat(dubbo).contains("\"group\":\"g1\"");
        assertThat(dubbo).contains("\"version\":\"1.0.0\"");

        // MyBatis: 1 statement, namespace filter applied
        String mybatis = responses.get(3).get("result").get("content").get(0).get("text").asText();
        assertThat(mybatis).contains("\"count\":1");
        assertThat(mybatis).contains("\"statementId\":\"selectById\"");
        assertThat(mybatis).contains("\"kind\":\"SELECT\"");

        // Dubbo method configs: 1 row (save, 5000ms, random, etc.)
        String methodCfgs = responses.get(4).get("result").get("content").get(0).get("text").asText();
        assertThat(methodCfgs).contains("\"count\":1");
        assertThat(methodCfgs).contains("\"methodName\":\"save\"");
        assertThat(methodCfgs).contains("\"timeoutMs\":5000");
        assertThat(methodCfgs).contains("\"retries\":3");
        assertThat(methodCfgs).contains("\"loadbalance\":\"random\"");
        assertThat(methodCfgs).contains("\"parent\":\"service\"");
        assertThat(methodCfgs).contains("\"parentId\":" + svcId);

        // MyBatis result maps: 1 row (orderResult, Order, 5 props, 1 assoc)
        String resultMaps = responses.get(5).get("result").get("content").get(0).get("text").asText();
        assertThat(resultMaps).contains("\"count\":1");
        assertThat(resultMaps).contains("\"mapId\":\"orderResult\"");
        assertThat(resultMaps).contains("\"typeFqn\":\"com.acme.Order\"");
        assertThat(resultMaps).contains("\"extendsRef\":\"baseResult\"");
        assertThat(resultMaps).contains("\"propertyCount\":5");
        assertThat(resultMaps).contains("\"associationCount\":1");

        // Spring Boot autoconfig: 1 row (MyAutoConfig from spring.factories, EnableAutoConfiguration)
        String autoconfigs = responses.get(6).get("result").get("content").get(0).get("text").asText();
        assertThat(autoconfigs).contains("\"count\":1");
        assertThat(autoconfigs).contains("\"classFqn\":\"com.acme.demo.MyAutoConfig\"");
        assertThat(autoconfigs).contains("\"sourceFormat\":\"factories\"");
        assertThat(autoconfigs).contains("\"keyInFactories\":\"org.springframework.boot.autoconfigure.EnableAutoConfiguration\"");
    }

    private List<JsonNode> readResponses(ByteArrayOutputStream out) throws Exception {
        return readAllLines(out);
    }

    /** Find the first line in {@code lines} whose {@code id} field equals
     * {@code targetId}. Returns null if not found. Notifications (no id) are
     * skipped — this is for response lines only. */
    private JsonNode findResponseWithId(List<JsonNode> lines, int targetId) {
        for (JsonNode line : lines) {
            if (line.has("id") && line.get("id").asInt() == targetId) return line;
        }
        return null;
    }

    /**
     * Read every JSON line from the captured output, including notifications
     * (which have no {@code id} field). Used by the subscribe test which
     * cares about notifications interleaved with responses.
     */
    private List<JsonNode> readAllLines(ByteArrayOutputStream out) throws Exception {
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        List<JsonNode> out2 = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            out2.add(M.readTree(line));
        }
        return out2;
    }
}
