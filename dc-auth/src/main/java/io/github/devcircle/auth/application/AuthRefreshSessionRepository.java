package io.github.devcircle.auth.application;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Optional;

/** Owns refresh-token rotation, family revocation and session persistence. */
final class AuthRefreshSessionRepository {
    private final JdbcClient jdbcClient;
    private final AuthSecretHasher hasher;
    private final AuthAccountRepository accounts;

    AuthRefreshSessionRepository(JdbcClient jdbcClient, AuthSecretHasher hasher, AuthAccountRepository accounts) {
        this.jdbcClient = jdbcClient;
        this.hasher = hasher;
        this.accounts = accounts;
    }

    void save(Long userId, String rawToken, String rawCsrf, String familyId, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO refresh_token(user_id, token_hash, csrf_hash, family_id, revoked, expires_at)
                        VALUES (:userId, :tokenHash, :csrfHash, :familyId, false, :expiresAt)
                        """)
                .param("userId", userId).param("tokenHash", hasher.hash(rawToken))
                .param("csrfHash", hasher.hash(rawCsrf)).param("familyId", familyId)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt)).update();
    }

    void saveIfAbsent(Long userId, String rawToken, String rawCsrf, String familyId, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO refresh_token(user_id, token_hash, csrf_hash, family_id, revoked, expires_at)
                        VALUES (:userId, :tokenHash, :csrfHash, :familyId, false, :expiresAt)
                        """)
                .param("userId", userId).param("tokenHash", hasher.hash(rawToken))
                .param("csrfHash", hasher.hash(rawCsrf)).param("familyId", familyId)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt)).update();
    }

    Optional<AuthTokenRepository.RefreshTokenView> findActive(String rawToken) {
        return jdbcClient.sql("""
                        SELECT user_id, expires_at
                        FROM refresh_token
                        WHERE token_hash = :tokenHash AND revoked = false AND expires_at > CURRENT_TIMESTAMP
                        """)
                .param("tokenHash", hasher.hash(rawToken))
                .query((rs, rowNum) -> new AuthTokenRepository.RefreshTokenView(
                        rs.getLong("user_id"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    AuthTokenRepository.Account rotate(String rawToken, String rawCsrf,
                                       String replacementToken, String replacementCsrf,
                                       Instant replacementExpiresAt) {
        RefreshSession session = loadForUpdate(rawToken)
                .orElseThrow(() -> new TokenRevokedException("登录状态已失效，请重新登录"));
        if (!hasher.constantTimeEquals(session.csrfHash(), hasher.hash(rawCsrf))) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("安全校验失败，请重新登录");
        }
        if (session.revoked() || !session.expiresAt().isAfter(Instant.now())) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("检测到刷新令牌重复使用，请重新登录");
        }
        AuthTokenRepository.Account account = accounts.findAccountById(session.userId())
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
                .param("replacementHash", hasher.hash(replacementToken)).param("id", session.id()).update();
        if (revoked != 1) {
            revokeFamily(session.familyId());
            throw new TokenRevokedException("检测到刷新令牌重复使用，请重新登录");
        }
        save(session.userId(), replacementToken, replacementCsrf, session.familyId(), replacementExpiresAt);
        return account;
    }

    void revoke(String rawToken, String rawCsrf) {
        Optional<RefreshSession> session = loadForUpdate(rawToken);
        if (session.isEmpty()) return;
        if (!hasher.constantTimeEquals(session.get().csrfHash(), hasher.hash(rawCsrf))) {
            revokeFamily(session.get().familyId());
            throw new TokenRevokedException("安全校验失败");
        }
        jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE id = :id")
                .param("id", session.get().id()).update();
    }

    void revokeFamily(String familyId) {
        jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE family_id = :familyId")
                .param("familyId", familyId).update();
    }

    void revokeUserSessions(Long userId) {
        jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE user_id = :userId AND revoked = false")
                .param("userId", userId).update();
    }

    private Optional<RefreshSession> loadForUpdate(String rawToken) {
        return jdbcClient.sql("""
                        SELECT id, user_id, csrf_hash, family_id, revoked, expires_at
                        FROM refresh_token WHERE token_hash = :tokenHash FOR UPDATE
                        """)
                .param("tokenHash", hasher.hash(rawToken))
                .query((rs, rowNum) -> new RefreshSession(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("csrf_hash"),
                        rs.getString("family_id"), rs.getBoolean("revoked"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    private record RefreshSession(Long id, Long userId, String csrfHash, String familyId,
                                  boolean revoked, Instant expiresAt) {
    }
}
