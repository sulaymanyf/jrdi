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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenPomParserTest {

    @Test
    void projectGav_reads_simple_pom(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Gav gav = MavenPomParser.projectGav(pom);
        assertThat(gav.group()).isEqualTo("com.acme");
        assertThat(gav.artifact()).isEqualTo("my-app");
        assertThat(gav.version()).isEqualTo("1.0.0");
    }

    @Test
    void directDependencies_returns_immediate_deps(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                            <version>6.1.0</version>
                            <scope>compile</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        List<MavenPomParser.Dependency> deps = MavenPomParser.directDependencies(pom);
        assertThat(deps).hasSize(3);
        // First (compile) — first in declaration order
        assertThat(deps.get(0).groupId()).isEqualTo("org.springframework");
        assertThat(deps.get(0).artifactId()).isEqualTo("spring-core");
        assertThat(deps.get(0).version()).isEqualTo("6.1.0");
        assertThat(deps.get(0).scope()).isEqualTo("compile");
        // Second — guava
        assertThat(deps.get(1).groupId()).isEqualTo("com.google.guava");
        assertThat(deps.get(1).scope()).isEqualTo("compile");  // default
        // Third — junit test scope
        assertThat(deps.get(2).scope()).isEqualTo("test");
    }

    @Test
    void directDependencies_resolves_property_placeholders(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <spring.version>6.1.0</spring.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                            <version>${spring.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        List<MavenPomParser.Dependency> deps = MavenPomParser.directDependencies(pom);
        assertThat(deps).hasSize(1);
        // ${spring.version} → 6.1.0
        assertThat(deps.get(0).version()).isEqualTo("6.1.0");
    }

    @Test
    void resolveGraph_with_depth_1_returns_only_direct_deps(@TempDir Path tmp) throws IOException {
        // Set up: project pom + a fake m2 layout with a dep pom
        Path projectPom = tmp.resolve("pom.xml");
        Files.writeString(projectPom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.lib</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        // m2 layout for the dep
        Path m2 = tmp.resolve("m2");
        Path libPomDir = m2.resolve("com/lib/lib/2.0.0");
        Files.createDirectories(libPomDir);
        Path libPom = libPomDir.resolve("lib-2.0.0.pom");
        Files.writeString(libPom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.lib</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.deeper</groupId>
                            <artifactId>deeper</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        // depth=1 returns only the direct dep
        List<MavenPomParser.Dependency> depth1 =
                MavenPomParser.resolveGraph(projectPom, 1, List.of(m2));
        assertThat(depth1).hasSize(1);
        assertThat(depth1.get(0).groupId()).isEqualTo("com.lib");

        // depth=2 also pulls in deeper
        List<MavenPomParser.Dependency> depth2 =
                MavenPomParser.resolveGraph(projectPom, 2, List.of(m2));
        assertThat(depth2).hasSize(2);
        assertThat(depth2).anyMatch(d -> d.groupId().equals("com.deeper"));
    }

    @Test
    void resolveGraph_skips_transitive_walk_for_missing_poms(@TempDir Path tmp) throws IOException {
        // Direct dep's pom doesn't exist in m2 — the transitive
        // walk should skip rather than throw.
        Path projectPom = tmp.resolve("pom.xml");
        Files.writeString(projectPom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.lib</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Path m2 = tmp.resolve("m2");
        Files.createDirectories(m2);  // empty m2 root
        List<MavenPomParser.Dependency> deps =
                MavenPomParser.resolveGraph(projectPom, 5, List.of(m2));
        assertThat(deps).hasSize(1);
    }

    @Test
    void directDependencies_resolves_unmanaged_version_from_parent_dependencyManagement(
            @TempDir Path tmp) throws IOException {
        // Simulates: project with no <dependencyManagement> of its own,
        // inherits from a parent BOM. Parent pom lives in m2 layout.
        Path m2 = tmp.resolve("m2");
        Path parentDir = m2.resolve("org/springframework/boot/spring-boot-dependencies/3.2.0");
        Files.createDirectories(parentDir);
        Path parentPom = parentDir.resolve("spring-boot-dependencies-3.2.0.pom");
        Files.writeString(parentPom, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-dependencies</artifactId>
                  <version>3.2.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.30</version>
                      </dependency>
                      <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path projectPom = tmp.resolve("pom.xml");
        Files.writeString(projectPom, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>rds-pg</artifactId>
                  <version>1.0.0</version>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>3.2.0</version>
                  </parent>
                  <dependencies>
                    <dependency>
                      <groupId>org.projectlombok</groupId>
                      <artifactId>lombok</artifactId>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        List<MavenPomParser.Dependency> deps =
                MavenPomParser.directDependencies(projectPom, List.of(m2));
        assertThat(deps).hasSize(2);
        var lombok = deps.stream().filter(d -> d.artifactId().equals("lombok")).findFirst().orElseThrow();
        assertThat(lombok.version()).isEqualTo("1.18.30");
        assertThat(lombok.scope()).isEqualTo("provided");
        var junit = deps.stream().filter(d -> d.artifactId().equals("junit")).findFirst().orElseThrow();
        assertThat(junit.version()).isEqualTo("4.13.2");
        assertThat(junit.scope()).isEqualTo("test");
    }

    @Test
    void directDependencies_resolves_versions_via_scope_import_bom(
            @TempDir Path tmp) throws IOException {
        // Simulates the spring-boot-starter-parent pattern: a thin
        // parent whose <dependencyManagement> is one entry — a BOM
        // import — and the real versions live in the imported BOM.
        Path m2 = tmp.resolve("m2");
        Path bomDir = m2.resolve("org/springframework/boot/spring-boot-dependencies/3.2.0");
        Files.createDirectories(bomDir);
        Path bomPom = bomDir.resolve("spring-boot-dependencies-3.2.0.pom");
        Files.writeString(bomPom, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-dependencies</artifactId>
                  <version>3.2.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.30</version>
                      </dependency>
                      <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                        <version>4.13.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path parentDir = m2.resolve("org/springframework/boot/spring-boot-starter-parent/3.2.0");
        Files.createDirectories(parentDir);
        Path parentPom = parentDir.resolve("spring-boot-starter-parent-3.2.0.pom");
        Files.writeString(parentPom, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-parent</artifactId>
                  <version>3.2.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>3.2.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path projectPom = tmp.resolve("pom.xml");
        Files.writeString(projectPom, """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>rds-pg</artifactId>
                  <version>1.0.0</version>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                  </parent>
                  <dependencies>
                    <dependency>
                      <groupId>org.projectlombok</groupId>
                      <artifactId>lombok</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        List<MavenPomParser.Dependency> deps =
                MavenPomParser.directDependencies(projectPom, List.of(m2));
        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).version()).isEqualTo("1.18.30");
        assertThat(deps.get(1).version()).isEqualTo("4.13.2");
    }
}
