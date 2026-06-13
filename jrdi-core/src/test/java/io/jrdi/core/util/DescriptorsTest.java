package io.jrdi.core.util;

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
