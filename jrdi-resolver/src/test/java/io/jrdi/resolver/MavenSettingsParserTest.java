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
 */package io.jrdi.resolver;

import io.jrdi.core.coord.Gav;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenSettingsParserTest {

    @Test
    void missing_file_returns_defaults(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.xml");
        Settings s = MavenSettingsParser.loadFromPath(missing);
        assertThat(s).isNotNull();
        assertThat(s.getLocalRepository()).contains(".m2/repository");
        assertThat(s.getMirrors()).isNotEmpty();
    }

    @Test
    void parses_real_settings(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("settings.xml");
        Files.writeString(file, """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <localRepository>/var/m2</localRepository>
                  <mirrors>
                    <mirror>
                      <id>my-mirror</id>
                      <mirrorOf>*</mirrorOf>
                      <url>https://nexus.local/maven2</url>
                    </mirror>
                  </mirrors>
                  <profiles>
                    <profile>
                      <id>acme</id>
                      <repositories>
                        <repository>
                          <id>acme-public</id>
                          <url>https://nexus.acme.com/maven-public</url>
                        </repository>
                      </repositories>
                    </profile>
                  </profiles>
                  <activeProfiles>
                    <activeProfile>acme</activeProfile>
                  </activeProfiles>
                </settings>
                """);
        Settings s = MavenSettingsParser.loadFromPath(file);
        assertThat(s.getLocalRepository()).isEqualTo("/var/m2");
        assertThat(s.getMirrors()).hasSize(1);
        assertThat(s.getMirrors().get(0).getMirrorOf()).isEqualTo("*");
        List<org.apache.maven.settings.Repository> all = MavenSettingsParser.collectAllRepositories(s);
        assertThat(all).extracting(org.apache.maven.settings.Repository::getId)
                .contains("acme-public");
    }

    @Test
    void env_override_via_system_property(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("custom.xml");
        Files.writeString(file, """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <localRepository>/var/m2-custom</localRepository>
                </settings>
                """);
        String prev = System.getProperty(MavenSettingsParser.SYS_PROP);
        try {
            System.setProperty(MavenSettingsParser.SYS_PROP, file.toString());
            Settings s = MavenSettingsParser.load();
            assertThat(s.getLocalRepository()).isEqualTo("/var/m2-custom");
        } finally {
            if (prev == null) System.clearProperty(MavenSettingsParser.SYS_PROP);
            else System.setProperty(MavenSettingsParser.SYS_PROP, prev);
        }
    }

    @Test
    void gav_parsing_round_trip() {
        Gav gav = Gav.parse("org.springframework.samples:spring-petclinic:3.0.0");
        assertThat(gav.group()).isEqualTo("org.springframework.samples");
        assertThat(gav.artifact()).isEqualTo("spring-petclinic");
        assertThat(gav.version()).isEqualTo("3.0.0");
    }
}
