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
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.execute("SET SESSION cte_max_recursion_depth = 12000");
        jdbc.update("""
                INSERT INTO notification(receiver_id, type, title, content, read_flag, created_at)
                WITH RECURSIVE seq(n) AS (
                    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000
                )
                SELECT 42, 'COMMENT', CONCAT('title-', n), CONCAT('content-', n), MOD(n, 2),
                       TIMESTAMPADD(SECOND, n, '2026-01-01')
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
