package io.github.devcircle.notification.config;

import java.time.Instant;

/**
 * Deployment-owned access-token verification boundary for the notification STOMP endpoint.
 * The notification domain deliberately does not know how access tokens are encoded.
 */
@FunctionalInterface
public interface NotificationAccessTokenVerifier {
    /**
     * Verifies an access token and returns the identity and absolute expiry needed to bound the
     * WebSocket session. Implementations must not return or retain the original token.
     *
     * @throws RuntimeException when the token is invalid, expired, not an access token, or has
     *                          an invalid subject
     */
    VerifiedAccessToken verify(String accessToken);

    record VerifiedAccessToken(long userId, Instant expiresAt) {
    }
}
