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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureDecoderTest {

    @Test
    void decodes_class_signature() {
        String decoded = SignatureDecoder.decodeClassSignature(
                "Ljava/util/HashMap<TK;TV;>;");
        assertThat(decoded).contains("super=");
        assertThat(decoded).contains("java.util.HashMap");
    }

    @Test
    void decodes_method_signature_with_generics() {
        String decoded = SignatureDecoder.decodeMethodSignature(
                "<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)Ljava/util/List<TT;>;");
        assertThat(decoded).contains("T");
        assertThat(decoded).contains("param=");
        assertThat(decoded).contains("returns=");
        assertThat(decoded).contains("java.util.List");
    }

    @Test
    void decodes_field_signature() {
        String decoded = SignatureDecoder.decodeFieldSignature(
                "Ljava/util/Map<Ljava/lang/String;Ljava/util/List<*>;>;");
        assertThat(decoded).contains("field=");
        assertThat(decoded).contains("java.util.Map");
        assertThat(decoded).contains("java.util.List");
    }

    @Test
    void null_passes_through() {
        assertThat(SignatureDecoder.decodeClassSignature(null)).isNull();
        assertThat(SignatureDecoder.decodeMethodSignature(null)).isNull();
        assertThat(SignatureDecoder.decodeFieldSignature(null)).isNull();
    }
}
