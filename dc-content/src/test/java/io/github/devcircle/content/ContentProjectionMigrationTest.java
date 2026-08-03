package io.github.devcircle.content;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class ContentProjectionMigrationTest {
    @Test
    void fingerprintMigrationBackfillsProjectionRowsAndKeepsOneVersionWriteCompatibility() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:content-projection-migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        createLegacyTable(jdbcClient, "interaction_projection_dedup");
        createLegacyTable(jdbcClient, "comment_projection_dedup");
        insertLegacyRow(jdbcClient, "interaction_projection_dedup",
                "11111111-1111-1111-1111-111111111111", "interaction.changed");
        insertLegacyRow(jdbcClient, "comment_projection_dedup",
                "22222222-2222-2222-2222-222222222222", "comment.changed");

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V19__bind_content_projection_payloads.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V21__order_content_counter_projections.sql"));
        }

        insertLegacyRow(jdbcClient, "interaction_projection_dedup",
                "33333333-3333-3333-3333-333333333333", "interaction.changed");
        insertLegacyRow(jdbcClient, "comment_projection_dedup",
                "44444444-4444-4444-4444-444444444444", "comment.changed");
        assertThat(legacyRows(jdbcClient, "interaction_projection_dedup")).isEqualTo(2);
        assertThat(legacyRows(jdbcClient, "comment_projection_dedup")).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM content_counter_projection_watermark")
                .query(Integer.class).single()).isZero();
    }

    private void createLegacyTable(JdbcClient jdbcClient, String table) {
        jdbcClient.sql("""
                        CREATE TABLE %s (
                            event_id CHAR(36) PRIMARY KEY,
                            topic VARCHAR(128) NOT NULL,
                            consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """.formatted(table))
                .update();
    }

    private void insertLegacyRow(JdbcClient jdbcClient, String table, String eventId, String topic) {
        jdbcClient.sql("INSERT INTO " + table + "(event_id, topic) VALUES (:eventId, :topic)")
                .param("eventId", eventId)
                .param("topic", topic)
                .update();
    }

    private int legacyRows(JdbcClient jdbcClient, String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE event_fingerprint = :legacyFingerprint")
                .param("legacyFingerprint", "0".repeat(64))
                .query(Integer.class)
                .single();
    }
}
