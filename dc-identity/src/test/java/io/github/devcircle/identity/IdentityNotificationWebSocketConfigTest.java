package io.github.devcircle.identity;

import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.notification.config.NotificationAccessTokenVerifier;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityNotificationWebSocketConfigTest {
    private static final String SECRET =
            "test-secret-that-is-long-enough-for-production-style-validation-123456";
    private final JwtService jwtService = new JwtService(SECRET);
    private final NotificationAccessTokenVerifier verifier =
            new IdentityNotificationWebSocketConfig().notificationAccessTokenVerifier(jwtService);

    @Test
    void delegatesAccessTokenVerificationToJwtServiceAndReturnsNumericSubject() {
        Instant expiresAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        String token = jwtService.issueAccess(17L, "Observer", List.of("USER"),
                expiresAt);

        NotificationAccessTokenVerifier.VerifiedAccessToken verified = verifier.verify(token);
        assertThat(verified.userId()).isEqualTo(17L);
        assertThat(verified.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void rejectsExpiredNonAccessAndNonNumericSubjects() {
        String expired = jwtService.issueAccess(17L, "Observer", List.of("USER"),
                Instant.now().minusSeconds(1));
        String refresh = token("17", "refresh");
        String nonNumeric = token("observer", "access");

        assertThatThrownBy(() -> verifier.verify(expired)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> verifier.verify(refresh))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access token");
        assertThatThrownBy(() -> verifier.verify(nonNumeric))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeric user ID");
    }

    private String token(String subject, String type) {
        return Jwts.builder()
                .subject(subject)
                .claim("type", type)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
