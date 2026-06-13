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
 */package io.jrdi.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptorsTest {

    @Test
    void primitive_descriptors() {
        assertThat(Descriptors.toReadable("I")).isEqualTo("int");
        assertThat(Descriptors.toReadable("Z")).isEqualTo("boolean");
        assertThat(Descriptors.toReadable("V")).isEqualTo("void");
    }

    @Test
    void object_descriptor() {
        assertThat(Descriptors.toReadable("Ljava/lang/String;")).isEqualTo("java.lang.String");
    }

    @Test
    void array_descriptor() {
        assertThat(Descriptors.toReadable("[[Ljava/util/List;")).isEqualTo("java.util.List[][]");
    }

    @Test
    void method_descriptor_params() {
        String[] p = Descriptors.parameterTypes("(Ljava/util/List;Ljava/lang/String;I)V");
        assertThat(p).containsExactly("Ljava/util/List;", "Ljava/lang/String;", "I");
    }

    @Test
    void method_descriptor_return() {
        assertThat(Descriptors.toReadableReturn("()Ljava/lang/Object;")).isEqualTo("java.lang.Object");
    }

    @Test
    void empty_params() {
        assertThat(Descriptors.parameterTypes("()V")).isEmpty();
    }
}
