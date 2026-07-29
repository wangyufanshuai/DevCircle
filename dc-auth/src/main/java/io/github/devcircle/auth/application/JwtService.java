package io.github.devcircle.auth.application;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    private final SecretKey key;

    public JwtService(@Value("${devcircle.jwt.secret}") String secret) {
        if (secret == null || secret.length() < 48 || secret.contains("change-me")) {
            throw new IllegalStateException("DEVCIRCLE_JWT_SECRET must contain at least 48 non-default characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
