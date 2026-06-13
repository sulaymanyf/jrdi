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
 */package io.jrdi.bytecode;

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
