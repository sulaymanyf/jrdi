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
 */package io.jrdi.core.coord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GavTest {

    @Test
    void parse_valid_gav() {
        Gav gav = Gav.parse("org.springframework:spring-core:5.3.30");
        assertThat(gav.group()).isEqualTo("org.springframework");
        assertThat(gav.artifact()).isEqualTo("spring-core");
        assertThat(gav.version()).isEqualTo("5.3.30");
    }

    @Test
    void parse_invalid_gav() {
        assertThatThrownBy(() -> Gav.parse("no-colons"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void path_layout() {
        Gav gav = Gav.of("com.acme", "lib", "1.0.0");
        assertThat(gav.path("/root")).isEqualTo("/root/com/acme/lib/1.0.0");
    }

    @Test
    void equality_and_hash() {
        Gav a = Gav.of("g", "a", "1.0.0");
        Gav b = Gav.of("g", "a", "1.0.0");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
