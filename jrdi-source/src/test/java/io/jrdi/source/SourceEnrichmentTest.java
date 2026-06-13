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
 */package io.jrdi.source;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SourceEnrichmentTest {

    @Test
    void sources_jar_round_trip_and_line_number_match() throws IOException {
        // The .class bytes are produced by the bytecode pass; here we just need a
        // sources.jar with the source text.
        Path sourcesJar = Files.createTempFile("src-", ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sourcesJar))) {
            z.putNextEntry(new ZipEntry("com/acme/Owner.java"));
            String src = """
                    package com.acme;
                    public class Owner {
                        public String firstName = "alice";
                        public String lastName = "liddell";
                        public String greet() {
                            String greeting = "hello";
                            greeting = greeting + " " + firstName;
                            return greeting;
                        }
                    }
                    """;
            z.write(src.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Optional<String> text = new SourceLoader().read(sourcesJar, Fqn.fromDotted("com.acme.Owner"));
        assertThat(text).isPresent();

        MethodMatcher matcher = new MethodMatcher();
        MethodKey greetKey = new MethodKey("greet", "()Ljava/lang/String;");
        Optional<MethodMatcher.SourceFacts> facts = matcher.match(text.get(),
                Fqn.fromDotted("com.acme.Owner"), greetKey);
        assertThat(facts).isPresent();
        assertThat(facts.get().hasLines()).isTrue();
        assertThat(facts.get().startLine()).isEqualTo(5);
        assertThat(facts.get().endLine()).isEqualTo(9);
    }

    @Test
    void missing_class_returns_empty() throws IOException {
        Path sourcesJar = Files.createTempFile("src-", ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sourcesJar))) {
            // empty
        }
        Optional<String> text = new SourceLoader().read(sourcesJar, Fqn.fromDotted("nope.NoClass"));
        assertThat(text).isEmpty();
    }

    @Test
    void finds_lambda_line_in_enclosing_method() throws IOException {
        String src = """
                package com.acme;
                import java.util.function.Function;
                public class WithLambda {
                    public int run() {
                        Function<String, Integer> f = (String s) -> Integer.parseInt(s);
                        return f.apply("42");
                    }
                }
                """;
        MethodMatcher matcher = new MethodMatcher();
        Optional<MethodMatcher.SourceFacts> facts = matcher.match(src,
                Fqn.fromDotted("com.acme.WithLambda"),
                new MethodKey("run", "()I"));
        assertThat(facts).isPresent();
        assertThat(facts.get().lambdas()).hasSize(1);
        assertThat(facts.get().lambdas().get(0).line()).isEqualTo(5);
    }

    @Test
    void nested_class_finds_inner_method() throws IOException {
        String src = """
                package com.acme;
                public class Outer {
                    public static class Inner {
                        public int deep() { return 42; }
                    }
                }
                """;
        MethodMatcher matcher = new MethodMatcher();
        Optional<MethodMatcher.SourceFacts> facts = matcher.match(src,
                Fqn.fromDotted("com.acme.Outer.Inner"),
                new MethodKey("deep", "()I"));
        assertThat(facts).isPresent();
        assertThat(facts.get().startLine()).isEqualTo(4);
    }
}
