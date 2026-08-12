package io.github.devcircle.identity;

import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.notification.config.NotificationAccessTokenVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdentityNotificationWebSocketConfig {
    @Bean
    NotificationAccessTokenVerifier notificationAccessTokenVerifier(JwtService jwtService) {
        return accessToken -> {
            var claims = jwtService.parseAccess(accessToken);
            String subject = claims.getSubject();
            long userId;
            try {
                userId = Long.parseLong(subject);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Access-token subject must be a numeric user ID", error);
            }
            if (userId <= 0) {
                throw new IllegalArgumentException("Access-token subject must be a positive user ID");
            }
            if (claims.getExpiration() == null) {
                throw new IllegalArgumentException("Access token must have an expiration");
            }
            return new NotificationAccessTokenVerifier.VerifiedAccessToken(
                    userId, claims.getExpiration().toInstant());
        };
    }
}
