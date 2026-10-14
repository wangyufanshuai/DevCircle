package io.github.devcircle.content;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.content.infrastructure.PostQueryRepository;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ContentReadPathIndexIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
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
        // Content's public cursor also reads File-owned media columns, as in the aggregate runtime.
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/file-migration").table("flyway_schema_history_file")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("""
                INSERT INTO post(id, author_id, title, status, theme, content_kind, difficulty,
                                 created_at, updated_at)
                WITH RECURSIVE high(n) AS (
                    SELECT 0 UNION ALL SELECT n + 1 FROM high WHERE n < 99
                ), low(n) AS (
                    SELECT 0 UNION ALL SELECT n + 1 FROM low WHERE n < 99
                ), seq(n) AS (
                    SELECT high.n * 100 + low.n + 1 FROM high CROSS JOIN low
                )
                SELECT n, 42, CONCAT('post-', n), 'PUBLISHED', 'DEDUCTION', 'CASE_FILE', 'MEDIUM',
                       TIMESTAMPADD(SECOND, n, '2026-01-01'),
                       TIMESTAMPADD(SECOND, n, '2026-01-01')
                FROM seq
                """);
        jdbc.execute("ANALYZE TABLE post");
        jdbc.update("INSERT INTO post_content(post_id, markdown) SELECT id, CONCAT('body-', id) FROM post");
        // Equal timestamps straddle a 20-row page boundary and must be ordered by id.
        jdbc.update("UPDATE post SET created_at='2026-01-02 00:00:00' WHERE id >= 9980");
    }

    @Test
    void channelAndSubmissionIndexesMatchTheirCursorQueries() {
        assertThat(indexColumns("post", "idx_post_channel_cursor"))
                .isEqualTo("status,theme,content_kind,difficulty,created_at,id");
        assertThat(indexColumns("post", "idx_post_author_updated"))
                .isEqualTo("author_id,updated_at,id");

        assertPlan("""
                SELECT id FROM post
                WHERE status = 'PUBLISHED'
                  AND theme = 'DEDUCTION'
                  AND content_kind = 'CASE_FILE'
                  AND difficulty = 'MEDIUM'
                  AND (created_at < CURRENT_TIMESTAMP
                       OR (created_at = CURRENT_TIMESTAMP AND id < 20000))
                ORDER BY created_at DESC, id DESC LIMIT 21
                """, "idx_post_channel_cursor");
        assertPlan("""
                SELECT id FROM post
                WHERE author_id = 42
                ORDER BY updated_at DESC LIMIT 50
                """, "idx_post_author_updated");
    }

    @Test
    void realContentCursorTraversesTenThousandRowsWithoutMissingOrRepeatingIds() {
        PostQueryRepository repository = new PostQueryRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        List<Long> expected = jdbc.queryForList(
                "SELECT id FROM post WHERE status='PUBLISHED' ORDER BY created_at DESC, id DESC", Long.class);
        List<Long> actual = new ArrayList<>();
        String cursor = "";
        do {
            var batch = repository.listCursor(cursor, 20, null, null, null, null);
            assertThat(batch.records()).hasSizeLessThanOrEqualTo(20);
            assertThat(batch.records()).allSatisfy(post -> {
                assertThat(post.aggregateVersion()).isNotNull().isNotNegative();
                assertThat(post.excerpt()).isEqualTo("body-" + post.id());
            });
            actual.addAll(batch.records().stream().map(post -> post.id()).toList());
            if (!batch.hasMore()) break;
            assertThat(batch.nextCursor()).isNotBlank().isNotEqualTo(cursor);
            cursor = batch.nextCursor();
            assertThat(actual.size()).isLessThanOrEqualTo(expected.size());
        } while (true);
        assertThat(actual).hasSize(10000).containsExactlyElementsOf(expected);
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
