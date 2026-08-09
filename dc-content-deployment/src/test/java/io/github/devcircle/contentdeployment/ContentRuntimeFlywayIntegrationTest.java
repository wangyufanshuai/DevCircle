package io.github.devcircle.contentdeployment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ContentRuntimeFlywayIntegrationTest {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @BeforeEach
    void resetDatabase() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (var connection = dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try (var resultSet = statement.executeQuery("SHOW TABLES")) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
            }
            for (String table : tables) {
                statement.execute("DROP TABLE `" + table.replace("`", "``") + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    @Test
    void freshDatabaseMigratesBothDomainsWithSeparateHistories() {
        try (var context = startAggregateMigrationContext()) {
            JdbcClient jdbc = jdbcClient();

            assertThat(tableExists(jdbc, "post")).isTrue();
            assertThat(tableExists(jdbc, "media_asset")).isTrue();
            assertThat(tableExists(jdbc, "file_idempotency_record")).isTrue();
            assertThat(successfulHistoryRows(jdbc, ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE)).isEqualTo(23);
            assertThat(successfulHistoryRows(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE)).isEqualTo(3);
            assertThat(latestVersion(jdbc, ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE)).isEqualTo("23");
            assertThat(latestVersion(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE)).isEqualTo("3");
            assertThat(historyType(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE, "1")).isEqualTo("BASELINE");

            assertThat(context.getBeansOfType(Flyway.class))
                    .containsOnlyKeys("contentFlyway", "fileFlyway");
        }
    }

    @Test
    void aggregateContinuesExistingContentAndFileHistoriesWithoutLosingMediaData() {
        legacyContentFlyway(MigrationVersion.fromVersion("22")).migrate();
        legacyFileFlyway(MigrationVersion.fromVersion("2")).migrate();
        JdbcClient jdbc = jdbcClient();
        jdbc.sql("""
                        INSERT INTO media_asset(object_key, url, content_type, size_bytes, status)
                        VALUES ('legacy/cover.webp', '', 'image/webp', 128, 'PENDING_REVIEW')
                        """).update();
        jdbc.sql("""
                        INSERT INTO file_idempotency_record(idempotency_key, user_id, operation, resource_id)
                        VALUES ('legacy-file-write', 42, 'COMPLETE_UPLOAD', 1)
                        """).update();

        try (var ignored = startAggregateMigrationContext()) {
            assertThat(latestVersion(jdbc, ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE)).isEqualTo("23");
            assertThat(latestVersion(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE)).isEqualTo("3");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM media_asset WHERE object_key = 'legacy/cover.webp'")
                    .query(Integer.class).single()).isOne();
            assertThat(jdbc.sql("""
                            SELECT request_fingerprint
                            FROM file_idempotency_record
                            WHERE idempotency_key = 'legacy-file-write'
                            """).query(String.class).single()).isEqualTo(LEGACY_FINGERPRINT);
        }
    }

    @Test
    void repeatedMigrateAndAggregateRestartAreIdempotent() {
        JdbcClient jdbc = jdbcClient();
        try (var context = startAggregateMigrationContext()) {
            jdbc.sql("""
                            INSERT INTO media_asset(object_key, url, content_type, size_bytes, status)
                            VALUES ('restart/sentinel.webp', '', 'image/webp', 256, 'PENDING_REVIEW')
                            """).update();

            assertThat(context.getBean("contentFlyway", Flyway.class).migrate().migrationsExecuted).isZero();
            assertThat(context.getBean("fileFlyway", Flyway.class).migrate().migrationsExecuted).isZero();
        }

        int contentHistoryRows = successfulHistoryRows(jdbc, ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE);
        int fileHistoryRows = successfulHistoryRows(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE);

        try (var ignored = startAggregateMigrationContext()) {
            assertThat(successfulHistoryRows(jdbc, ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE))
                    .isEqualTo(contentHistoryRows);
            assertThat(successfulHistoryRows(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE))
                    .isEqualTo(fileHistoryRows);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM media_asset WHERE object_key = 'restart/sentinel.webp'")
                    .query(Integer.class).single()).isOne();
            assertThat(failedHistoryRows(jdbc, ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE)).isZero();
            assertThat(failedHistoryRows(jdbc, ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE)).isZero();
        }
    }

    private AnnotationConfigApplicationContext startAggregateMigrationContext() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, this::dataSource);
        context.register(ContentRuntimeFlywayConfig.class);
        context.refresh();
        return context;
    }

    private Flyway legacyContentFlyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations(ContentRuntimeFlywayConfig.CONTENT_LOCATION)
                .table(ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE)
                .target(target)
                .load();
    }

    private Flyway legacyFileFlyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations(ContentRuntimeFlywayConfig.FILE_LOCATION)
                .table(ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(target)
                .load();
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private JdbcClient jdbcClient() {
        return JdbcClient.create(dataSource());
    }

    private boolean tableExists(JdbcClient jdbc, String table) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name = :table
                        """)
                .param("table", table)
                .query(Integer.class)
                .single() == 1;
    }

    private int successfulHistoryRows(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT COUNT(*) FROM `" + table + "` WHERE success = 1")
                .query(Integer.class)
                .single();
    }

    private int failedHistoryRows(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT COUNT(*) FROM `" + table + "` WHERE success = 0")
                .query(Integer.class)
                .single();
    }

    private String latestVersion(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT version FROM `" + table + "` WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1")
                .query(String.class)
                .single();
    }

    private String historyType(JdbcClient jdbc, String table, String version) {
        return jdbc.sql("SELECT type FROM `" + table + "` WHERE version = :version")
                .param("version", version)
                .query(String.class)
                .single();
    }
}
