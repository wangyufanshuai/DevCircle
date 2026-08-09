package io.github.devcircle.identity;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
class IdentityProductionSecurityConfig {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    @Bean
    SmartInitializingSingleton identityProductionSecurityGuard(
            @Value("${devcircle.production:false}") boolean production,
            @Value("${devcircle.public-origin:http://localhost:5501}") String publicOrigin,
            @Value("${devcircle.auth.cookie-secure:false}") boolean secureCookies,
            @Value("${devcircle.auth.frontend-callback-uri:http://localhost:5501/auth/callback}") String frontendCallback,
            @Value("${devcircle.auth.github.enabled:false}") boolean githubEnabled,
            @Value("${devcircle.auth.github.client-id:}") String githubClientId,
            @Value("${devcircle.auth.github.client-secret:}") String githubClientSecret,
            @Value("${devcircle.auth.github.callback-uri:http://localhost:8080/api/auth/oauth2/github/callback}") String githubCallback) {
        return () -> validateProductionConfiguration(
                production, publicOrigin, secureCookies, frontendCallback, githubEnabled,
                githubClientId, githubClientSecret, githubCallback);
    }

    static void validateProductionConfiguration(boolean production,
                                                String publicOrigin,
                                                boolean secureCookies,
                                                String frontendCallback,
                                                boolean githubEnabled,
                                                String githubClientId,
                                                String githubClientSecret,
                                                String githubCallback) {
        if (!production) {
            return;
        }
        if (!secureCookies) {
            throw new IllegalStateException("AUTH_COOKIE_SECURE must be true in production");
        }
        String origin = publicHttpsOrigin(publicOrigin, "PUBLIC_ORIGIN");
        requireCallback(frontendCallback, origin + "/auth/callback", "AUTH_FRONTEND_CALLBACK_URI");
        requireCallback(
                githubCallback, origin + "/api/auth/oauth2/github/callback", "GITHUB_CALLBACK_URI");
        if (githubEnabled) {
            requireCredential(githubClientId, "GITHUB_CLIENT_ID", 8);
            requireCredential(githubClientSecret, "GITHUB_CLIENT_SECRET", 20);
        }
    }

    private static String publicHttpsOrigin(String rawValue, String propertyName) {
        String value = rawValue == null ? "" : rawValue.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("not a public HTTPS origin");
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(propertyName + " must be a public HTTPS origin in production", error);
        }
    }

    private static void requireCallback(String actual, String expected, String propertyName) {
        if (actual == null || !actual.trim().equals(expected)) {
            throw new IllegalStateException(
                    propertyName + " must derive from PUBLIC_ORIGIN in production");
        }
    }

    private static void requireCredential(String value, String propertyName, int minimumLength) {
        String candidate = value == null ? "" : value.trim();
        String lowered = candidate.toLowerCase(Locale.ROOT);
        if (candidate.length() < minimumLength || lowered.contains("replace-with")
                || lowered.contains("changeme") || lowered.contains("local-development")) {
            throw new IllegalStateException(
                    propertyName + " is required when GitHub OAuth is enabled in production");
        }
    }
}
