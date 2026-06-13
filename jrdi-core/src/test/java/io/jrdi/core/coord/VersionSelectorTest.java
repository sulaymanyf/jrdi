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

class VersionSelectorTest {

    @Test
    void picks_higher_major() {
        assertThat(VersionSelector.highestVersion("1.9.0", "2.0.0")).isEqualTo("2.0.0");
    }

    @Test
    void picks_higher_minor() {
        assertThat(VersionSelector.highestVersion("1.5.3", "1.6.0")).isEqualTo("1.6.0");
    }

    @Test
    void picks_higher_patch() {
        assertThat(VersionSelector.highestVersion("1.5.3", "1.5.4")).isEqualTo("1.5.4");
    }

    @Test
    void keeps_first_when_equal() {
        assertThat(VersionSelector.highestVersion("1.0.0", "1.0.0")).isEqualTo("1.0.0");
    }

    @Test
    void gav_highest_version() {
        Gav a = Gav.of("g", "a", "1.0.0");
        Gav b = Gav.of("g", "a", "1.0.1");
        assertThat(VersionSelector.highestVersion(a, b)).isEqualTo(b);
    }

    @Test
    void gav_different_artifact_rejected() {
        Gav a = Gav.of("g", "a", "1.0.0");
        Gav b = Gav.of("g", "b", "1.0.0");
        assertThatThrownBy(() -> VersionSelector.highestVersion(a, b))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
