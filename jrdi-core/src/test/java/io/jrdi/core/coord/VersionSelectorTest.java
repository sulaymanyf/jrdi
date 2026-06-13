package io.jrdi.core.coord;

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
