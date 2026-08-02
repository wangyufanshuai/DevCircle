package io.github.devcircle.content;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ContentProjectionMigrationIntegrationTest {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @Test
    void v18RowsAreBackfilledAndOldWritersRemainCompatibleAfterV19() {
        Flyway v18 = flyway(MigrationVersion.fromVersion("18"));
        v18.migrate();
        JdbcClient jdbcClient = jdbcClient();

        insertLegacyRow(jdbcClient, "interaction_projection_dedup",
                "11111111-1111-1111-1111-111111111111", "interaction.changed");
        insertLegacyRow(jdbcClient, "comment_projection_dedup",
                "22222222-2222-2222-2222-222222222222", "comment.changed");

        flyway(null).migrate();

        assertThat(legacyRows(jdbcClient, "interaction_projection_dedup")).isEqualTo(1);
        assertThat(legacyRows(jdbcClient, "comment_projection_dedup")).isEqualTo(1);

        insertLegacyRow(jdbcClient, "interaction_projection_dedup",
                "33333333-3333-3333-3333-333333333333", "interaction.changed");
        insertLegacyRow(jdbcClient, "comment_projection_dedup",
                "44444444-4444-4444-4444-444444444444", "comment.changed");

        assertThat(legacyRows(jdbcClient, "interaction_projection_dedup")).isEqualTo(2);
        assertThat(legacyRows(jdbcClient, "comment_projection_dedup")).isEqualTo(2);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private JdbcClient jdbcClient() {
        var dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        return JdbcClient.create(dataSource);
    }

    private void insertLegacyRow(JdbcClient jdbcClient, String table, String eventId, String topic) {
        jdbcClient.sql("INSERT INTO " + table + "(event_id, topic) VALUES (:eventId, :topic)")
                .param("eventId", eventId)
                .param("topic", topic)
                .update();
    }

    private int legacyRows(JdbcClient jdbcClient, String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE event_fingerprint = :legacyFingerprint")
                .param("legacyFingerprint", LEGACY_FINGERPRINT)
                .query(Integer.class)
                .single();
    }
}
