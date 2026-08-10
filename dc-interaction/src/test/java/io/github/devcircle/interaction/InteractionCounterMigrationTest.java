package io.github.devcircle.interaction;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionCounterMigrationTest {
    @Test
    void projectionVersionDefaultsKeepThePreviousWriterCompatible() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:interaction-counter-migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        CREATE TABLE post_counter(
                            post_id BIGINT PRIMARY KEY,
                            like_count BIGINT NOT NULL DEFAULT 0,
                            favorite_count BIGINT NOT NULL DEFAULT 0,
                            comment_count BIGINT NOT NULL DEFAULT 0,
                            view_count BIGINT NOT NULL DEFAULT 0)
                        """).update();
        jdbcClient.sql("INSERT INTO post_counter(post_id, like_count) VALUES (1001, 3)").update();

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/interaction-migration/V4__interaction_counter_versions.sql"));
        }

        jdbcClient.sql("INSERT INTO post_counter(post_id, like_count) VALUES (1002, 4)").update();
        assertThat(jdbcClient.sql("""
                        SELECT post_id, projection_version FROM post_counter ORDER BY post_id
                        """).query((rs, rowNum) -> java.util.List.of(
                        rs.getLong("post_id"), rs.getLong("projection_version"))).list())
                .containsExactly(java.util.List.of(1001L, 0L), java.util.List.of(1002L, 0L));
    }
}
