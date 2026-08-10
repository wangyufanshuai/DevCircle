package io.github.devcircle.notification.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSocketConfigTest {
    @Test
    void acceptsAConcreteBrowserOrigin() {
        assertThatCode(() -> new WebSocketConfig("https://mysticverse.example.com/"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWildcardOrNonOriginValues() {
        assertThatThrownBy(() -> new WebSocketConfig("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concrete HTTP(S) origin");
        assertThatThrownBy(() -> new WebSocketConfig("https://mysticverse.example.com/private"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concrete HTTP(S) origin");
    }
}
