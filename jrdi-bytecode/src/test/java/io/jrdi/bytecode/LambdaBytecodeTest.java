package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LambdaBytecodeTest {

    @Test
    void captures_lambda_invokedynamic() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.WithLambda", """
                package com.acme;
                import java.util.function.Function;
                public class WithLambda {
                    public int run() {
                        Function<String, Integer> f = (String s) -> Integer.parseInt(s);
                        return f.apply("42");
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());

        // We expect: dynamic invoke (LambdaMetafactory) + the synthetic target.
        assertThat(r.lambdas()).isNotEmpty();
        assertThat(r.invokes())
                .anyMatch(e -> e.kind() == PassResult.Kind.DYNAMIC
                        && e.note() != null && e.note().contains("lambda$run$0"));
    }

    @Test
    void detects_method_reference() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.MethodRef", """
                package com.acme;
                import java.util.function.Function;
                public class MethodRef {
                    public int run() {
                        Function<String, Integer> f = Integer::parseInt;
                        return f.apply("7");
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());
        // Method references go through LambdaMetafactory just like lambdas
        assertThat(r.lambdas()).isNotEmpty();
    }
}
