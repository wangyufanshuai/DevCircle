package io.github.devcircle.auth.api;

import io.github.devcircle.auth.application.AuthService;
import io.github.devcircle.auth.application.AuthTokenRepository;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private static final String SECRET = "test-secret-that-is-long-enough-for-production-style-validation-123456";

    @Test
    void accessTokenCarriesOnlyAccessClaims() {
        JwtService jwtService = new JwtService(SECRET);
        String token = jwtService.issueAccess(42L, "林墨", List.of("USER"), Instant.now().plusSeconds(60));

        var claims = jwtService.parseAccess(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
    }

    @Test
    void registrationPersistsAnArgon2PasswordAndRefreshSession() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        JwtService jwtService = new JwtService(SECRET);
        var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        var account = new AuthTokenRepository.Account(7L, "reader@example.com", "星海读者", "unused", "ACTIVE", List.of("USER"));
        when(repository.createAccount(anyString(), anyString(), anyString())).thenReturn(account);
        AuthService service = new AuthService(repository, jwtService, encoder);

        AuthService.SessionTokens tokens = service.register("Reader@Example.com", "long-password-2026", "星海读者", "trace");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).hasSizeGreaterThan(32);
        assertThat(tokens.csrfToken()).hasSizeGreaterThan(32);
        verify(repository).createAccount(anyString(), anyString(), anyString());
        verify(repository).saveRefreshToken(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void weakPasswordIsRejectedBeforeDatabaseWrite() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        AuthService service = new AuthService(repository, new JwtService(SECRET), Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

        assertThatThrownBy(() -> service.register("reader@example.com", "short", "星海读者", "trace"))
                .isInstanceOf(BusinessException.class);
    }
}
