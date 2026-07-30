package io.github.devcircle.user.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LegacyProfileBackfillRepository {
    private final JdbcClient jdbcClient;
    private final String authSchema;

    public LegacyProfileBackfillRepository(JdbcClient jdbcClient,
                                           @Value("${devcircle.user.auth-schema:dc_auth}") String authSchema) {
        if (authSchema == null || !authSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid auth schema name");
        }
        this.jdbcClient = jdbcClient;
        this.authSchema = authSchema;
    }

    public int copyMissingProfiles(int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 1000));
        return jdbcClient.sql("""
                        INSERT INTO user_profile(user_id, username)
                        SELECT account.id, account.display_name
                        FROM %s.user_account account
                        LEFT JOIN user_profile profile ON profile.user_id = account.id
                        WHERE account.status = 'ACTIVE' AND profile.user_id IS NULL
                        ORDER BY account.id
                        LIMIT :batchSize
                        """.formatted(authSchema))
                .param("batchSize", batchSize)
                .update();
    }
}
