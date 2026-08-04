package io.github.devcircle.comment;

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
class CommentReadPathIndexIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_comment")
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
                INSERT INTO comment(post_id, author_id, parent_id, root_id, content, status, created_at)
                WITH RECURSIVE seq(n) AS (
                    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000
                )
                SELECT 1001, MOD(n, 500) + 1,
                       CASE WHEN MOD(n, 5) = 0 THEN NULL ELSE 500 END,
                       CASE WHEN MOD(n, 5) = 0 THEN NULL ELSE 500 END,
                       CONCAT('comment-', n),
                       CASE MOD(n, 4) WHEN 0 THEN 'DELETED' WHEN 1 THEN 'HIDDEN' ELSE 'VISIBLE' END,
                       TIMESTAMPADD(SECOND, n, '2026-01-01')
                FROM seq
                """);
        jdbc.execute("ANALYZE TABLE comment");
    }

    @Test
    void threadIndexesMatchVisibleRootAndReplyQueries() {
        assertThat(indexColumns("comment", "idx_comment_post_status_id"))
                .isEqualTo("post_id,status,id");
        assertThat(indexColumns("comment", "idx_comment_thread_root_id"))
                .isEqualTo("post_id,parent_id,id");
        assertThat(indexColumns("comment", "idx_comment_thread_reply_id"))
                .isEqualTo("post_id,root_id,id");

        assertPlan("""
                SELECT id FROM comment
                WHERE post_id = 1001 AND status = 'VISIBLE' AND id < 20000
                ORDER BY id DESC LIMIT 20
                """, "idx_comment_post_status_id");
        assertPlan("""
                SELECT id FROM comment
                WHERE post_id = 1001 AND parent_id IS NULL AND id < 20000
                ORDER BY id DESC LIMIT 20
                """, "idx_comment_thread_root_id");
        assertPlan("""
                SELECT id FROM comment
                WHERE post_id = 1001 AND root_id = 500
                ORDER BY id ASC
                """, "idx_comment_thread_reply_id");
    }

    private String indexColumns(String table, String index) {
        return jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, String.class, table, index);
    }

    private void assertPlan(String query, String expectedIndex) {
        Plan plan = jdbc.queryForObject("EXPLAIN " + query,
                (rs, rowNum) -> new Plan(rs.getString("possible_keys"),
                        rs.getString("key"), rs.getString("Extra")));
        assertThat(plan).isNotNull();
        assertThat(plan.possibleKeys()).contains(expectedIndex);
        assertThat(plan.key()).isEqualTo(expectedIndex);
        assertThat(plan.extra()).doesNotContain("filesort");
    }

    private record Plan(String possibleKeys, String key, String extra) {
    }
}
