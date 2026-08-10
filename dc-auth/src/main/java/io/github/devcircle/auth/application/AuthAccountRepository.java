package io.github.devcircle.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owns account and OAuth binding persistence; session and audit SQL stay elsewhere. */
final class AuthAccountRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuthAccountRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    Optional<AuthTokenRepository.Account> findAccountByEmail(String email) {
        return jdbcClient.sql("""
                        SELECT id, email, display_name, password_hash, status
                        FROM user_account
                        WHERE email = :email
                        """)
                .param("email", email)
                .query((rs, rowNum) -> account(
                        rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("status")))
                .optional();
    }

    Optional<AuthTokenRepository.Account> findAccountById(Long userId) {
        return jdbcClient.sql("""
                        SELECT id, email, display_name, password_hash, status
                        FROM user_account
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> account(
                        rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("status")))
                .optional();
    }

    Optional<AuthTokenRepository.Account> findAccountByOAuth(String provider, String providerUserId) {
        return jdbcClient.sql("""
                        SELECT ua.id, ua.email, ua.display_name, ua.password_hash, ua.status
                        FROM user_account ua
                        JOIN user_oauth_binding uob ON uob.user_id = ua.id
                        WHERE uob.provider = :provider AND uob.provider_user_id = :providerUserId
                        """)
                .param("provider", provider)
                .param("providerUserId", providerUserId)
                .query((rs, rowNum) -> account(
                        rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("status")))
                .optional();
    }

    AuthTokenRepository.Account createAccount(String email, String displayName, String passwordHash) {
        if (email == null || email.length() > 128 || displayName == null || displayName.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "账号资料长度超出限制");
        }
        try {
            jdbcClient.sql("""
                            INSERT INTO user_account(username, display_name, email, password_hash, status)
                            VALUES (:username, :displayName, :email, :passwordHash, 'ACTIVE')
                            """)
                    .param("username", email).param("displayName", displayName)
                    .param("email", email).param("passwordHash", passwordHash).update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "该邮箱已注册");
        }
        AuthTokenRepository.Account account = findAccountByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Created account cannot be loaded"));
        jdbcClient.sql("""
                        INSERT IGNORE INTO user_role(user_id, role_id)
                        SELECT :userId, id FROM role WHERE code = 'USER'
                        """)
                .param("userId", account.id()).update();
        AuthTokenRepository.Account created = findAccountById(account.id()).orElseThrow();
        writeUserRegistered(created);
        return created;
    }

    AuthTokenRepository.Account bindOrCreateOAuthAccount(String provider,
                                                          String providerUserId,
                                                          String email,
                                                          String displayName,
                                                          String unusablePasswordHash) {
        Optional<AuthTokenRepository.Account> bound = findAccountByOAuth(provider, providerUserId);
        if (bound.isPresent()) return bound.get();
        if (findAccountByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.OAUTH_ACCOUNT_CONFLICT,
                    "该邮箱已注册，请使用邮箱密码登录；系统不会自动合并账号");
        }
        AuthTokenRepository.Account account = createAccount(email, displayName, unusablePasswordHash);
        try {
            jdbcClient.sql("""
                            INSERT INTO user_oauth_binding(user_id, provider, provider_user_id)
                            VALUES (:userId, :provider, :providerUserId)
                            """)
                    .param("userId", account.id()).param("provider", provider)
                    .param("providerUserId", providerUserId).update();
        } catch (DuplicateKeyException ex) {
            return findAccountByOAuth(provider, providerUserId).orElseThrow();
        }
        return findAccountById(account.id()).orElseThrow();
    }

    private AuthTokenRepository.Account account(Long id, String email, String displayName,
                                                String passwordHash, String status) {
        return new AuthTokenRepository.Account(id, email, displayName, passwordHash, status, roles(id));
    }

    private void writeUserRegistered(AuthTokenRepository.Account account) {
        EventEnvelope event = EventEnvelope.now(EventTopics.USER_REGISTERED, "dc-auth", account.id().toString(),
                Map.of("userId", account.id(), "displayName", account.displayName(), "status", account.status()));
        try {
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'USER', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString()).param("topic", event.type())
                    .param("aggregateId", event.aggregateId())
                    .param("payload", objectMapper.writeValueAsString(event)).update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize user registration event", ex);
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
                .param("userId", userId).query(String.class).list();
        return result.isEmpty() ? List.of("USER") : List.copyOf(result);
    }
}
