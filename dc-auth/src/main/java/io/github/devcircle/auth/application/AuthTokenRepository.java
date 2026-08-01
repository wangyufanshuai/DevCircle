package io.github.devcircle.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AuthTokenRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuthTokenRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public Optional<Account> findAccountByEmail(String email) {
        return jdbcClient.sql("""
                        SELECT id, email, display_name, password_hash, status
                        FROM user_account
                        WHERE email = :email
                        """)
                .param("email", email)
                .query((rs, rowNum) -> new Account(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getString("status"),
                        roles(rs.getLong("id"))))
                .optional();
    }

    public Optional<Account> findAccountById(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, email, display_name, password_hash, status
                        FROM user_account
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new Account(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getString("status"),
                        roles(rs.getLong("id"))))
                .optional();
    }

    public Optional<Account> findAccountByOAuth(String provider, String providerUserId) {
        return jdbcClient.sql("""
                        SELECT ua.id, ua.email, ua.display_name, ua.password_hash, ua.status
                        FROM user_account ua
                        JOIN user_oauth_binding uob ON uob.user_id = ua.id
                        WHERE uob.provider = :provider AND uob.provider_user_id = :providerUserId
                        """)
                .param("provider", provider)
                .param("providerUserId", providerUserId)
                .query((rs, rowNum) -> new Account(
                        rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("status"), roles(rs.getLong("id"))))
                .optional();
    }

    @Transactional
    public Account createAccount(String email, String displayName, String passwordHash) {
        try {
            jdbcClient.sql("""
                            INSERT INTO user_account(username, display_name, email, password_hash, status)
                            VALUES (:username, :displayName, :email, :passwordHash, 'ACTIVE')
                            """)
                    .param("username", email)
                    .param("displayName", displayName)
                    .param("email", email)
                    .param("passwordHash", passwordHash)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "该邮箱已注册");
        }
        Account account = findAccountByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Created account cannot be loaded"));
        jdbcClient.sql("""
                        INSERT IGNORE INTO user_role(user_id, role_id)
                        SELECT :userId, id FROM role WHERE code = 'USER'
                        """)
                .param("userId", account.id())
                .update();
        Account created = findAccountById(account.id()).orElseThrow();
        writeUserRegistered(created);
        return created;
    }

    private void writeUserRegistered(Account account) {
        EventEnvelope event = EventEnvelope.now(EventTopics.USER_REGISTERED, "dc-auth", account.id().toString(),
                Map.of("userId", account.id(), "displayName", account.displayName(), "status", account.status()));
        try {
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'USER', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateId", event.aggregateId())
                    .param("payload", objectMapper.writeValueAsString(event))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize user registration event", ex);
        }
    }

    @Transactional
    public Account bindOrCreateOAuthAccount(String provider,
                                            String providerUserId,
                                            String email,
                                            String displayName,
                                            String unusablePasswordHash) {
        Optional<Account> bound = findAccountByOAuth(provider, providerUserId);
        if (bound.isPresent()) {
            return bound.get();
        }
        Optional<Account> existingAccount = findAccountByEmail(email);
        if (existingAccount.isPresent()) {
            throw new BusinessException(ErrorCode.OAUTH_ACCOUNT_CONFLICT,
                    "该邮箱已注册，请使用邮箱密码登录；系统不会自动合并账号");
        }
        Account account = createAccount(email, displayName, unusablePasswordHash);
        try {
            jdbcClient.sql("""
                            INSERT INTO user_oauth_binding(user_id, provider, provider_user_id)
                            VALUES (:userId, :provider, :providerUserId)
                            """)
                    .param("userId", account.id())
                    .param("provider", provider)
                    .param("providerUserId", providerUserId)
                    .update();
        } catch (DuplicateKeyException ex) {
            return findAccountByOAuth(provider, providerUserId).orElseThrow();
        }
        return findAccountById(account.id()).orElseThrow();
    }

    public void saveOAuthState(String rawState, String verifier, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO oauth_login_state(state_hash, verifier, expires_at, used)
                        VALUES (:stateHash, :verifier, :expiresAt, false)
                        """)
                .param("stateHash", hash(rawState))
                .param("verifier", verifier)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    @Transactional
    public String consumeOAuthState(String rawState) {
        OAuthState state = jdbcClient.sql("""
                        SELECT id, verifier
                        FROM oauth_login_state
                        WHERE state_hash = :stateHash AND used = false AND expires_at > CURRENT_TIMESTAMP
                        FOR UPDATE
                        """)
                .param("stateHash", hash(rawState))
                .query((rs, rowNum) -> new OAuthState(rs.getLong("id"), rs.getString("verifier")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.OAUTH_STATE_INVALID, "OAuth 登录状态无效或已过期"));
        jdbcClient.sql("UPDATE oauth_login_state SET used = true, used_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", state.id())
                .update();
        return state.verifier();
    }

    public void saveRefreshToken(Long userId,
                                 String rawToken,
                                 String rawCsrf,
                                 String familyId,
                                 Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO refresh_token(user_id, token_hash, csrf_hash, family_id, revoked, expires_at)
                        VALUES (:userId, :tokenHash, :csrfHash, :familyId, false, :expiresAt)
                        """)
                .param("userId", userId)
                .param("tokenHash", hash(rawToken))
                .param("csrfHash", hash(rawCsrf))
                .param("familyId", familyId)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    public void saveRefreshTokenIfAbsent(Long userId,
                                         String rawToken,
                                         String rawCsrf,
                                         String familyId,
                                         Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO refresh_token(user_id, token_hash, csrf_hash, family_id, revoked, expires_at)
                        VALUES (:userId, :tokenHash, :csrfHash, :familyId, false, :expiresAt)
                        """)
                .param("userId", userId)
                .param("tokenHash", hash(rawToken))
                .param("csrfHash", hash(rawCsrf))
                .param("familyId", familyId)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    public Optional<RefreshTokenView> findRefreshToken(String rawToken) {
        return jdbcClient.sql("""
                        SELECT user_id, expires_at FROM refresh_token WHERE token_hash = :tokenHash
                        """)
                .param("tokenHash", hash(rawToken))
                .query((rs, rowNum) -> new RefreshTokenView(rs.getLong("user_id"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Transactional(noRollbackFor = TokenRevokedException.class)
    public Account rotateRefreshToken(String rawToken,
                                      String rawCsrf,
                                      String replacementToken,
                                      String replacementCsrf,
                                      Instant replacementExpiresAt) {
        RefreshSession session = jdbcClient.sql("""
                        SELECT id, user_id, csrf_hash, family_id, revoked, expires_at
                        FROM refresh_token
                        WHERE token_hash = :tokenHash
                        FOR UPDATE
                        """)
                .param("tokenHash", hash(rawToken))
                .query((rs, rowNum) -> new RefreshSession(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("csrf_hash"),
                        rs.getString("family_id"),
                        rs.getBoolean("revoked"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional()
                .orElseThrow(() -> new TokenRevokedException("登录状态已失效，请重新登录"));
        if (!constantTimeEquals(session.csrfHash(), hash(rawCsrf))) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("安全校验失败，请重新登录");
        }
        if (session.revoked() || !session.expiresAt().isAfter(Instant.now())) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("检测到刷新令牌重复使用，请重新登录");
        }
        Account account = findAccountById(session.userId())
                .orElseThrow(() -> new IllegalStateException("Refresh token account no longer exists"));
        if (!"ACTIVE".equals(account.status())) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("账号当前不可用，请重新登录");
        }
        int revoked = jdbcClient.sql("""
                        UPDATE refresh_token
                        SET revoked = true, used_at = CURRENT_TIMESTAMP, replaced_by_hash = :replacementHash
                        WHERE id = :id AND revoked = false AND expires_at > CURRENT_TIMESTAMP
                        """)
                .param("replacementHash", hash(replacementToken))
                .param("id", session.id())
                .update();
        if (revoked != 1) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("检测到刷新令牌重复使用，请重新登录");
        }
        saveRefreshToken(session.userId(), replacementToken, replacementCsrf, session.familyId(), replacementExpiresAt);
        return account;
    }

    @Transactional(noRollbackFor = TokenRevokedException.class)
    public void revokeRefreshToken(String rawToken, String rawCsrf) {
        Optional<RefreshSession> session = jdbcClient.sql("""
                        SELECT id, user_id, csrf_hash, family_id, revoked, expires_at
                        FROM refresh_token
                        WHERE token_hash = :tokenHash AND revoked = false
                        FOR UPDATE
                        """)
                .param("tokenHash", hash(rawToken))
                .query((rs, rowNum) -> new RefreshSession(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("csrf_hash"), rs.getString("family_id"),
                        rs.getBoolean("revoked"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
        if (session.isEmpty()) {
            return;
        }
        if (!constantTimeEquals(session.get().csrfHash(), hash(rawCsrf))) {
            revokeFamily(session.get().familyId());
            throw new TokenRevokedException("安全校验失败");
        }
        jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE id = :id")
                .param("id", session.get().id())
                .update();
    }

    public void revokeFamily(String familyId) {
        jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE family_id = :familyId")
                .param("familyId", familyId)
                .update();
    }

    @Transactional
    public void revokeUserSessions(Long userId) {
        jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE user_id = :userId AND revoked = false")
                .param("userId", userId)
                .update();
    }

    public void audit(Long userId, String action, String targetType, String targetId, String traceId, Map<String, Object> metadata) {
        try {
            jdbcClient.sql("""
                            INSERT INTO audit_log(user_id, action, target_type, target_id, trace_id, metadata)
                            VALUES (:userId, :action, :targetType, :targetId, :traceId, CAST(:metadata AS JSON))
                            """)
                    .param("userId", userId)
                    .param("action", action)
                    .param("targetType", targetType)
                    .param("targetId", targetId)
                    .param("traceId", traceId)
                    .param("metadata", objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit metadata", ex);
        }
    }

    private List<String> roles(Long userId) {
        List<String> result = jdbcClient.sql("""
                        SELECT r.code
                        FROM role r
                        JOIN user_role ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
        return result.isEmpty() ? List.of("USER") : List.copyOf(result);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record Account(Long id,
                          String email,
                          String displayName,
                          String passwordHash,
                          String status,
                          List<String> roles) {
    }

    public record RefreshTokenView(Long userId, Instant expiresAt) {
    }

    private record RefreshSession(Long id, Long userId, String csrfHash, String familyId,
                                  boolean revoked, Instant expiresAt) {
    }

    private record OAuthState(Long id, String verifier) {
    }
}
