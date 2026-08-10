package io.github.devcircle.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compatibility facade for the authentication persistence API.
 *
 * <p>The public surface is intentionally stable while the SQL responsibilities
 * are owned by focused repositories. Keeping this facade for one migration
 * window avoids forcing every caller and test to change in the same release.</p>
 */
@Repository
public class AuthTokenRepository {
    private final AuthAccountRepository accounts;
    private final AuthOAuthStateRepository oauthStates;
    private final AuthRefreshSessionRepository refreshSessions;
    private final AuthAuditRepository audits;

    public AuthTokenRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        AuthSecretHasher hasher = new AuthSecretHasher();
        this.accounts = new AuthAccountRepository(jdbcClient, objectMapper);
        this.oauthStates = new AuthOAuthStateRepository(jdbcClient, hasher);
        this.refreshSessions = new AuthRefreshSessionRepository(jdbcClient, hasher, accounts);
        this.audits = new AuthAuditRepository(jdbcClient, objectMapper);
    }

    public Optional<Account> findAccountByEmail(String email) {
        return accounts.findAccountByEmail(email);
    }

    public Optional<Account> findAccountById(Long userId) {
        return accounts.findAccountById(userId);
    }

    public Optional<Account> findAccountByOAuth(String provider, String providerUserId) {
        return accounts.findAccountByOAuth(provider, providerUserId);
    }

    @Transactional
    public Account createAccount(String email, String displayName, String passwordHash) {
        return accounts.createAccount(email, displayName, passwordHash);
    }

    @Transactional
    public Account bindOrCreateOAuthAccount(String provider,
                                            String providerUserId,
                                            String email,
                                            String displayName,
                                            String unusablePasswordHash) {
        return accounts.bindOrCreateOAuthAccount(provider, providerUserId, email, displayName,
                unusablePasswordHash);
    }

    public void saveOAuthState(String rawState, String verifier, Instant expiresAt) {
        oauthStates.save(rawState, verifier, expiresAt);
    }

    @Transactional
    public String consumeOAuthState(String rawState) {
        return oauthStates.consume(rawState);
    }

    public void saveRefreshToken(Long userId,
                                 String rawToken,
                                 String rawCsrf,
                                 String familyId,
                                 Instant expiresAt) {
        refreshSessions.save(userId, rawToken, rawCsrf, familyId, expiresAt);
    }

    public void saveRefreshTokenIfAbsent(Long userId,
                                         String rawToken,
                                         String rawCsrf,
                                         String familyId,
                                         Instant expiresAt) {
        refreshSessions.saveIfAbsent(userId, rawToken, rawCsrf, familyId, expiresAt);
    }

    public Optional<RefreshTokenView> findActiveRefreshToken(String rawToken) {
        return refreshSessions.findActive(rawToken);
    }

    @Transactional(noRollbackFor = TokenRevokedException.class)
    public Account rotateRefreshToken(String rawToken,
                                      String rawCsrf,
                                      String replacementToken,
                                      String replacementCsrf,
                                      Instant replacementExpiresAt) {
        return refreshSessions.rotate(rawToken, rawCsrf, replacementToken, replacementCsrf,
                replacementExpiresAt);
    }

    @Transactional(noRollbackFor = TokenRevokedException.class)
    public void revokeRefreshToken(String rawToken, String rawCsrf) {
        refreshSessions.revoke(rawToken, rawCsrf);
    }

    public void revokeFamily(String familyId) {
        refreshSessions.revokeFamily(familyId);
    }

    @Transactional
    public void revokeUserSessions(Long userId) {
        refreshSessions.revokeUserSessions(userId);
    }

    public void audit(Long userId,
                      String action,
                      String targetType,
                      String targetId,
                      String traceId,
                      Map<String, Object> metadata) {
        audits.write(userId, action, targetType, targetId, traceId, metadata);
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
}
