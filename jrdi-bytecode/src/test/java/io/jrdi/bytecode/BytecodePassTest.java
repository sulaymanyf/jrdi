package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BytecodePassTest {

    @Test
    void visits_simple_class_and_finds_method() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.Greeter", """
                package com.acme;
                public class Greeter {
                    public String greet(String name) {
                        return "hello " + name;
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());
        assertThat(r.fqn().dotted()).isEqualTo("com.acme.Greeter");
        assertThat(r.superFqn()).contains(Fqn.fromDotted("java.lang.Object"));
        assertThat(r.methods()).hasSize(2); // <init> + greet
        assertThat(r.methods())
                .extracting(PassResult.MethodInfo::name)
                .containsExactlyInAnyOrder("<init>", "greet");
    }

    @Test
    void captures_virtual_invokes() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.UseGreeter", """
                package com.acme;
                public class UseGreeter {
                    public static class Greeter {
                        public String greet(String name) { return "hello " + name; }
                    }
                    public String run() {
                        Greeter g = new Greeter();
                        return g.greet("world");
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());
        List<PassResult.InvokeEdge> invokes = r.invokes();
        assertThat(invokes).extracting(PassResult.InvokeEdge::calleeName)
                .contains("greet", "<init>");
    }

    @Test
    void captures_static_invoke() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.UseMath", """
                package com.acme;
                public class UseMath {
                    public int run() {
                        return Math.max(1, 2);
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());
        assertThat(r.invokes())
                .filteredOn(e -> e.calleeName().equals("max"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo(PassResult.Kind.STATIC);
                    assertThat(e.calleeOwner()).isEqualTo("java/lang/Math");
                });
    }

    @Test
    void records_line_numbers() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.Lines", """
                package com.acme;
                public class Lines {
                    public String run() {
                        String a = "first";
                        String b = "second";
                        String c = a + b;
                        return c;
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());
        var method = r.methods().stream().filter(m -> m.name().equals("run")).findFirst().orElseThrow();
        assertThat(method.startLine()).isNotNull().isPositive();
        assertThat(method.endLine()).isNotNull().isGreaterThanOrEqualTo(method.startLine());
    }

    @Test
    void detects_class_for_name() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.Reflect", """
                package com.acme;
                public class Reflect {
                    public Object make() throws Exception {
                        return Class.forName("java.util.ArrayList").getDeclaredConstructor().newInstance();
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());
        assertThat(r.invokes())
                .anyMatch(e -> e.kind() == PassResult.Kind.REFLECT
                        && "java/util/ArrayList".equals(e.calleeOwner())
                        && e.confidence() == io.jrdi.core.edge.Confidence.PROBABLE);
        assertThat(r.invokes())
                .anyMatch(e -> e.kind() == PassResult.Kind.REFLECT
                        && "?".equals(e.calleeOwner())
                        && e.confidence() == io.jrdi.core.edge.Confidence.UNCERTAIN);
    }
}
