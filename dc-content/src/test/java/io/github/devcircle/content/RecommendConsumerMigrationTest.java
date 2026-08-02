package io.github.devcircle.content;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendConsumerMigrationTest {
    @Test
    void fingerprintMigrationBackfillsExistingRowsAndKeepsOneVersionWriteCompatibility() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:recommend-consumer-migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        CREATE TABLE recommend_consumer_dedup (
                            event_id CHAR(36) PRIMARY KEY,
                            topic VARCHAR(128) NOT NULL,
                            status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
                            attempts INT NOT NULL DEFAULT 1,
                            last_error VARCHAR(1000),
                            consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            processed_at TIMESTAMP,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """)
                .update();
        jdbcClient.sql("""
                        INSERT INTO recommend_consumer_dedup(event_id, topic)
                        VALUES ('11111111-1111-1111-1111-111111111111', 'interaction.changed')
                        """)
                .update();

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V18__bind_recommend_consumer_payloads.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V20__bind_recommend_consumer_leases.sql"));
        }

        jdbcClient.sql("""
                        INSERT INTO recommend_consumer_dedup(event_id, topic)
                        VALUES ('22222222-2222-2222-2222-222222222222', 'follow.changed')
                        """)
                .update();
        Integer legacyRows = jdbcClient.sql("""
                        SELECT COUNT(*) FROM recommend_consumer_dedup
                        WHERE event_fingerprint = :legacyFingerprint
                        """)
                .param("legacyFingerprint", "0".repeat(64))
                .query(Integer.class)
                .single();
        assertThat(legacyRows).isEqualTo(2);
        Integer leaseColumns = jdbcClient.sql("""
                        SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = 'RECOMMEND_CONSUMER_DEDUP' AND COLUMN_NAME = 'LEASE_TOKEN'
                        """)
                .query(Integer.class).single();
        assertThat(leaseColumns).isEqualTo(1);
    }
}
