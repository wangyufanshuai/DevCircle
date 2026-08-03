package io.github.devcircle.comment;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class CommentCounterMigrationTest {
    @Test
    void counterSnapshotBackfillsOnlyVisibleCommentsAndCanBeSafelyRecomputed() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:comment-counter-migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        CREATE TABLE comment(
                            id BIGINT PRIMARY KEY,
                            post_id BIGINT NOT NULL,
                            status VARCHAR(32) NOT NULL)
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO comment(id, post_id, status) VALUES
                        (1, 1001, 'VISIBLE'), (2, 1001, 'VISIBLE'),
                        (3, 1001, 'HIDDEN'), (4, 1002, 'DELETED')
                        """).update();

        executeMigration(dataSource);

        assertThat(count(jdbcClient, 1001L)).isEqualTo(2L);
        assertThat(version(jdbcClient, 1001L)).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM post_comment_counter WHERE post_id = 1002")
                .query(Long.class).single()).isZero();

        jdbcClient.sql("UPDATE comment SET status = 'HIDDEN' WHERE id IN (1, 2)").update();
        executeMigration(dataSource);
        assertThat(count(jdbcClient, 1001L)).isZero();
        assertThat(version(jdbcClient, 1001L)).isZero();
    }

    private void executeMigration(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V6__comment_counter_snapshot.sql"));
        }
    }

    private long count(JdbcClient jdbcClient, long postId) {
        return jdbcClient.sql("SELECT visible_count FROM post_comment_counter WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .single();
    }

    private long version(JdbcClient jdbcClient, long postId) {
        return jdbcClient.sql("SELECT projection_version FROM post_comment_counter WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .single();
    }
}
