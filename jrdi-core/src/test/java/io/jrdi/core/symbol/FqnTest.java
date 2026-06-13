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
 */package io.jrdi.core.symbol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FqnTest {

    @Test
    void slashed_form_is_preserved() {
        Fqn f = Fqn.of("java/lang/String");
        assertThat(f.slashed()).isEqualTo("java/lang/String");
        assertThat(f.dotted()).isEqualTo("java.lang.String");
    }

    @Test
    void fromDotted_converts_dots() {
        Fqn f = Fqn.fromDotted("com.acme.Foo");
        assertThat(f.slashed()).isEqualTo("com/acme/Foo");
    }

    @Test
    void package_check() {
        Fqn f = Fqn.fromDotted("com.acme.foo.Bar");
        assertThat(f.isInPackage("com.acme")).isTrue();
        assertThat(f.isInPackage("com.acme.foo")).isTrue();
        assertThat(f.isInPackage("com.other")).isFalse();
        assertThat(f.isInPackage("")).isTrue();
    }

    @Test
    void empty_fqn_rejected() {
        assertThatThrownBy(() -> new Fqn(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void binary_name_extracted() {
        Fqn f = Fqn.fromDotted("java.lang.String");
        assertThat(f.binaryName()).isEqualTo("String");
        assertThat(f.packageName()).isEqualTo("java.lang");
    }
}
