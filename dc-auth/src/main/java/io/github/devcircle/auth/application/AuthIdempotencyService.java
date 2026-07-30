package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
public class AuthIdempotencyService {
    private static final int WINDOW_MINUTES = 10;
    private final JdbcClient jdbcClient;
    private final byte[] hmacKey;

    public AuthIdempotencyService(JdbcClient jdbcClient,
                                  @Value("${devcircle.jwt.secret}") String signingSecret) {
        if (signingSecret == null || signingSecret.length() < 48 || signingSecret.contains("change-me")) {
            throw new IllegalStateException("DEVCIRCLE_JWT_SECRET must contain at least 48 non-default characters");
        }
        this.jdbcClient = jdbcClient;
        this.hmacKey = sha256(signingSecret + ":auth-idempotency");
    }

    @Transactional
    public AuthService.SessionTokens executeSession(String idempotencyKey,
                                                    String operation,
                                                    String requestMaterial,
                                                    Supplier<AuthService.SessionTokens> action,
                                                    Supplier<AuthService.SessionTokens> replayAction) {
        return execute(idempotencyKey, operation, requestMaterial, action, replayAction);
    }

    @Transactional
    public void executeVoid(String idempotencyKey,
                            String operation,
                            String requestMaterial,
                            Runnable action) {
        execute(idempotencyKey, operation, requestMaterial, () -> {
            action.run();
            return null;
        }, () -> null);
    }

    /** Derives an opaque session token without persisting the raw token. */
    public String deriveOpaqueToken(String purpose, String seed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] value = mac.doFinal((purpose + "\u0000" + seed).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is required", ex);
        }
    }

    private <T> T execute(String idempotencyKey,
                          String operation,
                          String requestMaterial,
                          Supplier<T> action,
                          Supplier<T> replayAction) {
        requireKey(idempotencyKey);
        String fingerprint = fingerprint(operation, requestMaterial);
        Instant expiresAt = Instant.now().plus(WINDOW_MINUTES, ChronoUnit.MINUTES);
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO auth_idempotency_record(
                            idempotency_key, operation, request_fingerprint, status, expires_at)
                        VALUES (:key, :operation, :fingerprint, 'PROCESSING', :expiresAt)
                        """)
                .param("key", idempotencyKey)
                .param("operation", operation)
                .param("fingerprint", fingerprint)
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        if (inserted == 0) {
            AuthIdempotencyRecord existing = find(idempotencyKey);
            if (!operation.equals(existing.operation()) || !constantTimeEquals(fingerprint, existing.requestFingerprint())) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他认证请求");
            }
            if (existing.expiresAt().isAfter(Instant.now())) {
                if (!"COMPLETED".equals(existing.status())) {
                    throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同认证请求正在处理中");
                }
                return replayAction.get();
            }
            int reclaimed = jdbcClient.sql("""
                            UPDATE auth_idempotency_record
                            SET operation = :operation, request_fingerprint = :fingerprint,
                                status = 'PROCESSING', user_id = NULL, expires_at = :expiresAt
                            WHERE idempotency_key = :key AND expires_at <= CURRENT_TIMESTAMP
                            """)
                    .param("key", idempotencyKey)
                    .param("operation", operation)
                    .param("fingerprint", fingerprint)
                    .param("expiresAt", Timestamp.from(expiresAt))
                    .update();
            if (reclaimed == 0) {
                AuthIdempotencyRecord raced = find(idempotencyKey);
                if ("COMPLETED".equals(raced.status()) && raced.expiresAt().isAfter(Instant.now())) {
                    return replayAction.get();
                }
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同认证请求正在处理中");
            }
        }

        T result = action.get();
        Long userId = result instanceof AuthService.SessionTokens tokens && tokens.user() != null
                ? tokens.user().userId() : null;
        jdbcClient.sql("""
                        UPDATE auth_idempotency_record
                        SET status = 'COMPLETED', user_id = :userId
                        WHERE idempotency_key = :key AND request_fingerprint = :fingerprint
                        """)
                .param("userId", userId)
                .param("key", idempotencyKey)
                .param("fingerprint", fingerprint)
                .update();
        return result;
    }

    private AuthIdempotencyRecord find(String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT operation, request_fingerprint, status, user_id, expires_at
                        FROM auth_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new AuthIdempotencyRecord(
                        rs.getString("operation"), rs.getString("request_fingerprint"),
                        rs.getString("status"), (Long) rs.getObject("user_id"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Auth idempotency record disappeared"));
    }

    private String fingerprint(String operation, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((operation + "\u0000" + (material == null ? "" : material))
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is required", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "认证写操作必须提供有效的 Idempotency-Key");
        }
    }

    private record AuthIdempotencyRecord(String operation, String requestFingerprint, String status,
                                         Long userId, Instant expiresAt) {
    }
}
