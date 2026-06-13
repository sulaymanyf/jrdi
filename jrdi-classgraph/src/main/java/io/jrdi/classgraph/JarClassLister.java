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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Enumerates the class files inside a single jar. Each entry is reported as a slashed FQN
 * (e.g. {@code com/acme/Foo}).
 *
 * <p>We use plain {@link JarFile} instead of ClassGraph here for two reasons:
 * (a) the U2 surface is "give me the list of classes in this jar", which a zip walk solves
 * with no classpath shadowing; (b) the heavyweight ClassGraph instance is reserved for the
 * U3 BytecodePass where we want full hierarchical info (annotations, modifiers, etc.).
 */
public final class JarClassLister {

    private static final Logger LOG = LoggerFactory.getLogger(JarClassLister.class);

    public List<String> list(Path jarPath) {
        List<String> out = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.endsWith(".class")) continue;
                if (name.endsWith("module-info.class")) continue;
                String fqn = name.substring(0, name.length() - 6);
                out.add(fqn);
            }
        } catch (IOException e) {
            LOG.warn("failed to read jar {}: {}", jarPath, e.getMessage());
        }
        return out;
    }
}
