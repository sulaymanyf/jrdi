package io.jrdi.decompile;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

class CfrDecompilerTest {

    @Test
    void decompiles_class_to_readable_text() throws IOException {
        byte[] classBytes = compile("com.acme.DecompileTarget", """
                package com.acme;
                public class DecompileTarget {
                    public int square(int n) {
                        return n * n;
                    }
                }
                """);
        String text = new CfrDecompiler().decompileBytes(classBytes, "com/acme/DecompileTarget");
        assertThat(text).isNotNull();
        assertThat(text).contains("class DecompileTarget");
        assertThat(text).contains("square");
    }

    @Test
    void assign_lines_to_method_via_cfr() throws IOException {
        byte[] classBytes = compile("com.acme.VirtualLines", """
                package com.acme;
                public class VirtualLines {
                    public int doIt() {
                        int x = 1;
                        int y = 2;
                        return x + y;
                    }
                }
                """);
        var facts = new VirtualLineAssigner().assign(
                classBytes,
                Fqn.fromDotted("com.acme.VirtualLines"),
                new MethodKey("doIt", "()I"));
        assertThat(facts).isPresent();
        assertThat(facts.get().hasLines()).isTrue();
    }

    @Test
    void assign_lines_with_args() throws IOException {
        byte[] classBytes = compile("com.acme.WithArgs", """
                package com.acme;
                public class WithArgs {
                    public String concat(String a, String b) {
                        return a + b;
                    }
                }
                """);
        var facts = new VirtualLineAssigner().assign(
                classBytes,
                Fqn.fromDotted("com.acme.WithArgs"),
                new MethodKey("concat", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        assertThat(facts).isPresent();
        assertThat(facts.get().hasLines()).isTrue();
    }

    @Test
    void lambda_lines_in_decompiled_output() throws IOException {
        byte[] classBytes = compile("com.acme.WithLambda", """
                package com.acme;
                import java.util.function.Function;
                public class WithLambda {
                    public int run() {
                        Function<String, Integer> f = (String s) -> Integer.parseInt(s);
                        return f.apply("42");
                    }
                }
                """);
        var facts = new VirtualLineAssigner().assign(
                classBytes,
                Fqn.fromDotted("com.acme.WithLambda"),
                new MethodKey("run", "()I"));
        assertThat(facts).isPresent();
        assertThat(facts.get().lambdas()).hasSize(1);
    }

    private static byte[] compile(String fqn, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
            outputs.put(fqn, new ByteArrayOutputStream());
            List<JavaFileObject> units = List.of(new InMemSource(fqn, source));
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, cfm, null,
                    List.of("-g", "-Xlint:none"),
                    null, units);
            boolean ok = task.call();
            if (!ok) throw new IllegalStateException("compilation failed");
            byte[] bytes = outputs.get(fqn).toByteArray();
            assertThat(bytes).isNotEmpty();
            return bytes;
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
