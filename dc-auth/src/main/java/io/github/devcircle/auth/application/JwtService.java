package io.github.devcircle.auth.application;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
public class JwtService {
    private final SecretKey key;

    public JwtService(String secret) {
        this(secret, false);
    }

    @Autowired
    public JwtService(@Value("${devcircle.jwt.secret}") String secret,
                      @Value("${devcircle.production:false}") boolean production) {
        validateSecret(secret, production);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateSecret(String secret, boolean production) {
        String normalized = secret == null ? "" : secret.toLowerCase(Locale.ROOT);
        if (secret == null || secret.length() < 48 || normalized.contains("change-me")) {
            throw new IllegalStateException("DEVCIRCLE_JWT_SECRET must contain at least 48 non-default characters");
        }
        if (production && (secret.length() < 64
                || normalized.contains("replace-with")
                || normalized.contains("local-development")
                || normalized.contains("example")
                || normalized.contains("default")
                || secret.codePoints().distinct().count() < 16)) {
            throw new IllegalStateException(
                    "DEVCIRCLE_JWT_SECRET must contain at least 64 high-diversity non-placeholder characters in production");
        }
    }

    public String issueAccess(Long userId, String username, List<String> roles, Instant expiresAt) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", String.join(",", roles))
                .claim("type", "access")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public Claims parseAccess(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!"access".equals(claims.get("type", String.class))) {
            throw new IllegalArgumentException("Expected an access token");
        }
        return claims;
    }
}
