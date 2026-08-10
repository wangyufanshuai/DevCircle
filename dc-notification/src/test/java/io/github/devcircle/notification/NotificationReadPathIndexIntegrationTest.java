package io.github.devcircle.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class NotificationReadPathIndexIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_notification")
            .withUsername("devcircle")
            .withPassword("devcircle");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/notification-migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("""
                INSERT INTO notification(receiver_id, type, title, content, read_flag, created_at)
                WITH RECURSIVE high(n) AS (
                    SELECT 0 UNION ALL SELECT n + 1 FROM high WHERE n < 99
                ), low(n) AS (
                    SELECT 0 UNION ALL SELECT n + 1 FROM low WHERE n < 99
                ), seq(n) AS (
                    SELECT high.n * 100 + low.n + 1 FROM high CROSS JOIN low
                )
                SELECT MOD(n, 100) + 1, 'COMMENT', CONCAT('title-', n), CONCAT('content-', n), MOD(n, 2),
                       TIMESTAMPADD(SECOND, n, '2026-01-01')
                FROM seq
                """);
        jdbc.update("""
                INSERT INTO notification(receiver_id, type, title, content, read_flag, created_at)
                WITH RECURSIVE high(n) AS (
                    SELECT 0 UNION ALL SELECT n + 1 FROM high WHERE n < 99
                ), low(n) AS (
                    SELECT 0 UNION ALL SELECT n + 1 FROM low WHERE n < 99
                ), seq(n) AS (
                    SELECT high.n * 100 + low.n + 1 FROM high CROSS JOIN low
                )
                SELECT 42, 'COMMENT', CONCAT('hot-title-', n), CONCAT('hot-content-', n), MOD(n, 2),
                       TIMESTAMPADD(SECOND, n, '2026-02-01')
                FROM seq
                """);
        jdbc.execute("ANALYZE TABLE notification");
    }

    @Test
    void inboxCursorIndexesMatchFilteredAndUnfilteredQueries() {
        assertThat(indexColumns("notification", "idx_notification_receiver_id"))
                .isEqualTo("receiver_id,id");
        assertThat(indexColumns("notification", "idx_notification_receiver_type_id"))
                .isEqualTo("receiver_id,type,id");

        Plan unfiltered = plan("""
                SELECT id FROM notification
                WHERE receiver_id = 42 AND id < 20000
                ORDER BY id DESC LIMIT 20
                """);
        assertThat(unfiltered.possibleKeys()).contains("idx_notification_receiver_id");
        assertThat(unfiltered.key()).isEqualTo("idx_notification_receiver_id");
        assertThat(unfiltered.extra()).doesNotContain("filesort");

        Plan filtered = plan("""
                SELECT id FROM notification
                WHERE receiver_id = 42 AND type = 'COMMENT' AND id < 20000
                ORDER BY id DESC LIMIT 20
                """);
        assertThat(filtered.possibleKeys()).contains("idx_notification_receiver_type_id");
        assertThat(filtered.key()).isEqualTo("idx_notification_receiver_type_id");
        assertThat(filtered.extra()).doesNotContain("filesort");
    }

    private String indexColumns(String table, String index) {
        return jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, String.class, table, index);
    }

    private Plan plan(String query) {
        return jdbc.queryForObject("EXPLAIN " + query,
                (rs, rowNum) -> new Plan(rs.getString("possible_keys"),
                        rs.getString("key"), rs.getString("Extra")));
    }

    private record Plan(String possibleKeys, String key, String extra) {
    }
}
