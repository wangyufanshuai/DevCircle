package io.github.devcircle.search;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class SearchMigrationTest {
    @Test
    void freshSearchDatabaseCreatesConsumerStateTables() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:search-migration;MODE=MySQL;DB_CLOSE_DELAY=-1");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Integer tables = jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_name IN ('SEARCH_CONSUMER_DEDUP', 'SEARCH_EVENT_WATERMARK')
                        """)
                .query(Integer.class)
                .single();
        assertThat(tables).isEqualTo(2);
    }
}
