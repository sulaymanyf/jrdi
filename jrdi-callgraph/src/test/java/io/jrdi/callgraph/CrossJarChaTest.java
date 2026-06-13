package io.jrdi.callgraph;

import io.jrdi.core.symbol.Fqn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the cross-jar CHA path:
 * <ol>
 *   <li>A class compiled into jar {@code ext.jar} defines a parent type with a method.</li>
 *   <li>A class compiled into jar {@code usr.jar} extends the parent, calls the method,
 *       and is the only thing indexed by the {@code ChaResolver}.</li>
 *   <li>When CHA encounters the parent FQN, it should reach out to the m2-style
 *       {@code M2ClasspathResolver} and find the method on the external type.</li>
 * </ol>
 */
class CrossJarChaTest {

    @Test
    void cha_finds_method_on_external_parent_via_m2_resolver(@TempDir Path tmp) throws Exception {
        // 1. Compile two tiny classes: a parent "Animal" with a "speak" method, and a
        //    child "Dog" that extends Animal and calls speak().
        Map<String, String> sources = Map.of(
                "com.ext.Animal",
                "package com.ext;\n" +
                "public class Animal { public String speak() { return \"...\"; } }\n",
                "com.usr.Dog",
                "package com.usr;\n" +
                "import com.ext.Animal;\n" +
                "public class Dog extends Animal { public String bark() { return speak(); } }\n");
        Map<String, byte[]> compiled = compileAll(sources);

        // 2. Build the "external" jar (the dependency) and the "user" jar (the indexed one).
        Path extJar = tmp.resolve("ext-1.0.0.jar");
        Path usrJar = tmp.resolve("usr-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(extJar))) {
            writeEntry(z, "com/ext/Animal.class", compiled.get("com.ext.Animal"));
        }
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(usrJar))) {
            writeEntry(z, "com/usr/Dog.class", compiled.get("com.usr.Dog"));
        }

        // 3. Wire a ChaResolver that knows only about Dog's parent link (no Dog->Animal
        //    entry in the children map), backed by an M2 resolver that finds Animal
        //    in ext-1.0.0.jar.
        M2ClasspathResolver m2 = new M2ClasspathResolver(List.of(tmp));
        ChaResolver cha = new ChaResolver(
                Map.of(Fqn.fromDotted("com.usr.Dog"), Fqn.fromDotted("com.ext.Animal")),
                Set.of(), Set.of(), m2);

        // 4. Walk the parent of Dog → should find Animal even though it's not in the
        //    initial children map.
        Optional<Fqn> animal = cha.parentOf(Fqn.fromDotted("com.usr.Dog"));
        assertThat(animal).isPresent();
        assertThat(animal.get().dotted()).isEqualTo("com.ext.Animal");

        // 5. Resolve the "speak" method on Animal via the external resolver. CHA's
        //    declaringType walks up the chain.
        Optional<Fqn> declaring = cha.declaringType(
                Fqn.fromDotted("com.ext.Animal"), "speak", "()Ljava/lang/String;");
        assertThat(declaring).isPresent();
        assertThat(declaring.get().dotted()).isEqualTo("com.ext.Animal");

        // 6. Sanity: an unknown method returns empty.
        Optional<Fqn> none = cha.declaringType(
                Fqn.fromDotted("com.ext.Animal"), "nope", "()V");
        assertThat(none).isEmpty();
    }

    @Test
    void m2_resolver_finds_class_in_jar(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> compiled = compileAll(Map.of(
                "com.ext.Greeter",
                "package com.ext;\n" +
                "public class Greeter { public String greet() { return \"hi\"; } }\n"));
        Path extJar = tmp.resolve("ext-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(extJar))) {
            writeEntry(z, "com/ext/Greeter.class", compiled.get("com.ext.Greeter"));
        }
        M2ClasspathResolver m2 = new M2ClasspathResolver(List.of(tmp));
        Optional<ExternalClassResolver.ExternalClass> hit = m2.resolve(
                Fqn.fromDotted("com.ext.Greeter"));
        assertThat(hit).isPresent();
        assertThat(hit.get().fqn().dotted()).isEqualTo("com.ext.Greeter");
        assertThat(hit.get().superFqn().dotted()).isEqualTo("java.lang.Object");
        assertThat(hit.get().methods()).hasSize(2); // ctor + greet
    }

    @Test
    void m2_resolver_returns_empty_for_missing_class(@TempDir Path tmp) {
        M2ClasspathResolver m2 = new M2ClasspathResolver(List.of(tmp));
        assertThat(m2.resolve(Fqn.fromDotted("com.does.NotExist"))).isEmpty();
    }

    private static void writeEntry(ZipOutputStream z, String name, byte[] bytes) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(bytes);
        z.closeEntry();
    }

    private static Map<String, byte[]> compileAll(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new java.util.HashMap<>();
            List<JavaFileObject> units = new java.util.ArrayList<>();
            for (var e : sources.entrySet()) {
                units.add(new InMemSource(e.getKey(), e.getValue()));
            }
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            List<String> errors = new java.util.ArrayList<>();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, cfm, d -> errors.add(d.toString()),
                    List.of("-g", "-Xlint:none"), null, units);
            Boolean ok = task.call();
            if (ok == null || !ok) {
                throw new IllegalStateException("compile failed: " + errors);
            }
            Map<String, byte[]> result = new java.util.HashMap<>();
            for (String fqn : sources.keySet()) {
                ByteArrayOutputStream baos = outputs.get(fqn);
                if (baos != null && baos.size() > 0) result.put(fqn, baos.toByteArray());
            }
            return result;
        }
    }

    private static final class InMemSource extends SimpleJavaFileObject {
        private final String source;
        InMemSource(String fqn, String source) {
            super(URI.create("string:///" + fqn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class CapturingFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputs;
        CapturingFileManager(StandardJavaFileManager delegate, Map<String, ByteArrayOutputStream> outputs) {
            super(delegate);
            this.outputs = outputs;
        }
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream baos = outputs.computeIfAbsent(className, k -> new ByteArrayOutputStream());
            return new SimpleJavaFileObject(URI.create("bytes:///" + className.replace('.', '/') + ".class"),
                    JavaFileObject.Kind.CLASS) {
                @Override
                public OutputStream openOutputStream() {
                    baos.reset();
                    return baos;
                }
            };
        }
    }
}
