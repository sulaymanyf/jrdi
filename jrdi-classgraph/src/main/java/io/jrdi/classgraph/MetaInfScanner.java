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
 */package io.jrdi.classgraph;

import io.github.classgraph.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Lightweight scanner for {@code META-INF/} resources inside a jar. Used in U2 to
 * discover {@code spring.factories}, {@code META-INF/services/*}, and similar
 * extension points that drive framework analyzers in P2.
 */
public final class MetaInfScanner {

    private static final Logger LOG = LoggerFactory.getLogger(MetaInfScanner.class);

    public List<MetaEntry> scan(Path jarPath) {
        List<MetaEntry> out = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream()
                    .filter(e -> !e.isDirectory() && e.getName().startsWith("META-INF/"))
                    .forEach(e -> out.add(new MetaEntry(e.getName(), readBytes(jar, e))));
        } catch (IOException e) {
            LOG.warn("MetaInf scan failed for {}: {}", jarPath, e.getMessage());
        }
        return Collections.unmodifiableList(out);
    }

    public List<String> listServiceProviders(Path jarPath, String serviceInterface) {
        String key = "META-INF/services/" + serviceInterface;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry e = jar.getJarEntry(key);
            if (e == null) return List.of();
            String body = new String(readBytes(jar, e));
            List<String> out = new ArrayList<>();
            for (String line : body.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
            return out;
        } catch (IOException e) {
            LOG.debug("no {} in {}", key, jarPath);
            return List.of();
        }
    }

    public List<String> listSpringFactories(Path jarPath, String key) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry e = jar.getJarEntry("META-INF/spring.factories");
            if (e == null) return List.of();
            java.util.Properties props = new java.util.Properties();
            try (InputStream in = jar.getInputStream(e)) {
                props.load(in);
            }
            String v = props.getProperty(key, "").trim();
            if (v.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            for (String s : v.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } catch (IOException ex) {
            LOG.debug("no spring.factories in {}", jarPath);
            return List.of();
        }
    }

    private byte[] readBytes(JarFile jar, JarEntry e) {
        try (InputStream in = jar.getInputStream(e)) {
            return in.readAllBytes();
        } catch (IOException io) {
            LOG.debug("read failed for {}: {}", e.getName(), io.getMessage());
            return new byte[0];
        }
    }

    public record MetaEntry(String name, byte[] bytes) {
        public String asString() {
            return new String(bytes);
        }
    }
}
