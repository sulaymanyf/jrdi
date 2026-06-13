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

/**
 * Static MCP resource: the V1 schema in human-readable form, exposed at
 * {@code jrdi://schema}. Useful for LLM clients that want a one-shot
 * description of what facts are stored.
 */
final class SchemaV1 {

    static final String SCHEMA_JSON = """
            {
              "version": 1,
              "tables": [
                {"name": "repos",          "purpose": "Indexed repositories (one row per jrdi index call)"},
                {"name": "artifacts",      "purpose": "Per-repo artifacts (group, name, version, sha256)"},
                {"name": "files",          "purpose": "Per-artifact source files (.java etc.)"},
                {"name": "classes",        "purpose": "Class declarations with access flags, super, interfaces, source"},
                {"name": "methods",        "purpose": "Per-class method declarations with start/end line numbers"},
                {"name": "fields",         "purpose": "Per-class field declarations"},
                {"name": "invokes",        "purpose": "Caller -> callee edges with kind and confidence"},
                {"name": "lambdas",        "purpose": "Lambda expressions mapped to enclosing + synthetic methods"},
                {"name": "issues",         "purpose": "Indexer-detected anomalies (missing source, uncertain reflect, ...)"},
                {"name": "spring_beans",   "purpose": "Spring-managed beans (annotation + XML + alias), by type, source, scope"},
                {"name": "spring_injects", "purpose": "@Autowired / @Resource / @Value sites and candidate beans"},
                {"name": "spring_boot_autoconfigs", "purpose": "Spring Boot auto-configurations from spring.factories + AutoConfiguration.imports (V5)"},
                {"name": "spring_autoconfig_conditions", "purpose": "@Conditional* annotations extracted from auto-config classes (V6)"},
                {"name": "dubbo_services", "purpose": "Dubbo providers (annotation + XML), interface -> impl"},
                {"name": "dubbo_references","purpose": "Dubbo consumers (annotation + XML), interface -> field"},
                {"name": "dubbo_method_configs", "purpose": "<dubbo:method> per-method tuning (timeout, retries, loadbalance, async, sent)"},
                {"name": "dubbo_registries", "purpose": "<dubbo:registry> declarations: address, protocol, port, parameters (V7)"},
                {"name": "mybatis_statements","purpose": "MyBatis SQL (annotation + XML) with template + JSqlParser-normalized form"},
                {"name": "mybatis_result_maps","purpose": "<resultMap> row-mapper shape (type, extends, property/association/collection counts)"}
              ],
              "toolNames": [
                "index_status", "find_symbol", "describe_method",
                "callers_of", "callees_of", "find_path", "list_issues",
                "find_spring_beans", "find_spring_injects", "find_spring_autoconfigs",
                "find_spring_autoconfig_conditions",
                "find_dubbo_services", "find_dubbo_references", "find_dubbo_method_configs",
                "find_dubbo_registries",
                "find_mybatis_statements", "find_mybatis_result_maps"
              ]
            }
            """;

    private SchemaV1() {
    }
}
