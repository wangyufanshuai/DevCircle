package io.github.devcircle.user;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class UserConsumerMigrationTest {
    @Test
    void fingerprintMigrationBackfillsExistingRowsAndKeepsOneVersionWriteCompatibility() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:user-consumer-migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        CREATE TABLE consumer_dedup (
                            event_id CHAR(36) PRIMARY KEY,
                            topic VARCHAR(128) NOT NULL,
                            consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """)
                .update();
        jdbcClient.sql("""
                        INSERT INTO consumer_dedup(event_id, topic)
                        VALUES ('11111111-1111-1111-1111-111111111111', 'user.registered')
                        """)
                .update();

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V6__bind_user_consumer_payloads.sql"));
        }

        jdbcClient.sql("""
                        INSERT INTO consumer_dedup(event_id, topic)
                        VALUES ('22222222-2222-2222-2222-222222222222', 'user.registered')
                        """)
                .update();
        Integer legacyRows = jdbcClient.sql("""
                        SELECT COUNT(*) FROM consumer_dedup
                        WHERE event_fingerprint = :legacyFingerprint
                        """)
                .param("legacyFingerprint", "0".repeat(64))
                .query(Integer.class)
                .single();
        assertThat(legacyRows).isEqualTo(2);
    }
}
