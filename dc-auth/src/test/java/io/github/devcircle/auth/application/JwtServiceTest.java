package io.github.devcircle.auth.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String STRONG_SECRET =
            "Aa1!Bb2@Cc3#Dd4$Ee5%Ff6^Gg7&Hh8*Ii9(Jj0)Kk1_Ll2+Mm3-Nn4=Oo5?Pp6Qq7~";

    @Test
    void productionRejectsDocumentedPlaceholderSecret() {
        assertThatThrownBy(() -> new JwtService(
                "replace-with-at-least-64-random-characters-replace-with-placeholder", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
    }

    @Test
    void productionRejectsLowDiversitySecret() {
        assertThatThrownBy(() -> new JwtService("a".repeat(72), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
    }

    @Test
    void productionAcceptsLongHighDiversitySecret() {
        assertThatCode(() -> new JwtService(STRONG_SECRET, true)).doesNotThrowAnyException();
    }

    @Test
    void developmentKeepsExplicitLocalSecretAvailable() {
        assertThatCode(() -> new JwtService(
                "local-development-secret-must-be-at-least-forty-eight-characters-long", false))
                .doesNotThrowAnyException();
    }
}
