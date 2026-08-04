package io.github.devcircle.interaction;

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
class InteractionReadPathIndexIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_interaction")
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
                INSERT INTO favorite_record(user_id, post_id, created_at)
                WITH RECURSIVE seq(n) AS (
                    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000
                )
                SELECT 42, n, TIMESTAMPADD(SECOND, n, '2026-01-01')
                FROM seq
                """);
        jdbc.execute("ANALYZE TABLE favorite_record");
    }

    @Test
    void favoriteTimelineIndexMatchesNewestFirstDashboardQuery() {
        assertThat(indexColumns("favorite_record", "idx_favorite_user_created_post"))
                .isEqualTo("user_id,created_at,post_id");
        Plan plan = plan("""
                SELECT post_id FROM favorite_record
                WHERE user_id = 42
                ORDER BY created_at DESC LIMIT 50
                """);
        assertThat(plan.possibleKeys()).contains("idx_favorite_user_created_post");
        assertThat(plan.key()).isEqualTo("idx_favorite_user_created_post");
        assertThat(plan.extra()).doesNotContain("filesort");
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
