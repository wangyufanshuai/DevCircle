package io.github.devcircle.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.function.Supplier;

@Service
public class AuthIdempotencyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int WINDOW_MINUTES = 10;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final byte[] encryptionKey;

    public AuthIdempotencyService(JdbcClient jdbcClient,
                                  ObjectMapper objectMapper,
                                  @Value("${devcircle.jwt.secret}") String signingSecret) {
        if (signingSecret == null || signingSecret.length() < 48 || signingSecret.contains("change-me")) {
            throw new IllegalStateException("DEVCIRCLE_JWT_SECRET must contain at least 48 non-default characters");
        }
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.encryptionKey = sha256(signingSecret + ":auth-idempotency");
    }

    @Transactional
    public AuthService.SessionTokens executeSession(String idempotencyKey,
                                                    String operation,
                                                    String requestMaterial,
                                                    Supplier<AuthService.SessionTokens> action) {
        return execute(idempotencyKey, operation, requestMaterial, action, AuthService.SessionTokens.class);
    }

    @Transactional
    public void executeVoid(String idempotencyKey,
                            String operation,
                            String requestMaterial,
                            Runnable action) {
        execute(idempotencyKey, operation, requestMaterial, () -> {
            action.run();
            return "DONE";
        }, String.class);
    }

    private <T> T execute(String idempotencyKey,
                          String operation,
                          String requestMaterial,
                          Supplier<T> action,
                          Class<T> responseType) {
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
                return decrypt(existing.responsePayload(), responseType, idempotencyKey, operation, fingerprint);
            }
            int reclaimed = jdbcClient.sql("""
                            UPDATE auth_idempotency_record
                            SET operation = :operation, request_fingerprint = :fingerprint,
                                status = 'PROCESSING', response_payload = NULL, user_id = NULL,
                                expires_at = :expiresAt
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
                    return decrypt(raced.responsePayload(), responseType, idempotencyKey,
                            raced.operation(), raced.requestFingerprint());
                }
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同认证请求正在处理中");
            }
        }

        T result = action.get();
        String encrypted = encrypt(result, idempotencyKey, operation, fingerprint);
        Long userId = result instanceof AuthService.SessionTokens tokens && tokens.user() != null
                ? tokens.user().userId() : null;
        jdbcClient.sql("""
                        UPDATE auth_idempotency_record
                        SET status = 'COMPLETED', response_payload = :responsePayload, user_id = :userId
                        WHERE idempotency_key = :key AND request_fingerprint = :fingerprint
                        """)
                .param("responsePayload", encrypted)
                .param("userId", userId)
                .param("key", idempotencyKey)
                .param("fingerprint", fingerprint)
                .update();
        return result;
    }

    private AuthIdempotencyRecord find(String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT operation, request_fingerprint, status, response_payload, expires_at
                        FROM auth_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new AuthIdempotencyRecord(
                        rs.getString("operation"), rs.getString("request_fingerprint"),
                        rs.getString("status"), rs.getString("response_payload"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Auth idempotency record disappeared"));
    }

    private <T> T decrypt(String value, Class<T> responseType, String key, String operation, String fingerprint) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Auth idempotency response is missing");
        }
        try {
            String[] parts = value.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, Base64.getUrlDecoder().decode(parts[0])));
            cipher.updateAAD(aad(key, operation, fingerprint));
            byte[] clear = cipher.doFinal(Base64.getUrlDecoder().decode(parts[1]));
            return objectMapper.readValue(clear, responseType);
        } catch (GeneralSecurityException | IOException | RuntimeException ex) {
            throw new IllegalStateException("Unable to restore auth idempotent response", ex);
        }
    }

    private String encrypt(Object value, String key, String operation, String fingerprint) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(key, operation, fingerprint));
            byte[] encrypted = cipher.doFinal(objectMapper.writeValueAsBytes(value));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException | JsonProcessingException ex) {
            throw new IllegalStateException("Unable to persist auth idempotent response", ex);
        }
    }

    private byte[] aad(String key, String operation, String fingerprint) {
        return (key + ":" + operation + ":" + fingerprint).getBytes(StandardCharsets.UTF_8);
    }

    private String fingerprint(String operation, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(encryptionKey, "HmacSHA256"));
            return HexFormatHolder.format(mac.doFinal((operation + "\u0000" + (material == null ? "" : material))
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
                                         String responsePayload, Instant expiresAt) {
    }

    private static final class HexFormatHolder {
        private static String format(byte[] value) {
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(Character.forDigit((item >>> 4) & 0x0F, 16));
                result.append(Character.forDigit(item & 0x0F, 16));
            }
            return result.toString();
        }
    }
}
