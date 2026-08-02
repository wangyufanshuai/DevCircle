package io.github.devcircle.file.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.file.api.UploadIntentRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(MediaRepositoryStateTest.TestConfig.class)
class MediaRepositoryStateTest {
    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private MediaRepository repository;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        createMediaTable();
        jdbcClient.sql("""
                CREATE TABLE file_idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(48) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    resource_id BIGINT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
    }

    @Test
    void uploadIntentBindsMetadataButNotGeneratedStorageCoordinates() {
        UploadIntentRequest request = image("cover.png", 128, 640, 480);

        var created = repository.createIntent(7L, "intent-key", "private/first.png", "internal/first.png",
                request, EXPIRES_AT);
        var replay = repository.createIntent(7L, "intent-key", "private/retry.png", "internal/retry.png",
                request, EXPIRES_AT.plusSeconds(30));

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.objectKey()).isEqualTo("private/first.png");
        assertThat(count("media_asset")).isEqualTo(1);
        assertThat(idempotencyCount("intent-key")).isEqualTo(1);

        assertConflict(() -> repository.createIntent(7L, "intent-key", "private/changed.png",
                "internal/changed.png", image("changed.png", 256, 800, 600), EXPIRES_AT));
        assertConflict(() -> repository.createIntent(8L, "intent-key", "private/foreign.png",
                "internal/foreign.png", request, EXPIRES_AT));
        assertThat(count("media_asset")).isEqualTo(1);
    }

    @Test
    void legacyUploadFingerprintRemainsReplayCompatibleForOneVersion() {
        var created = repository.createIntent(7L, "legacy-file-key", "private/original.png",
                "internal/original.png", image("original.png", 128, 640, 480), EXPIRES_AT);
        jdbcClient.sql("""
                        UPDATE file_idempotency_record SET request_fingerprint = :legacy
                        WHERE idempotency_key = 'legacy-file-key'
                        """).param("legacy", "0".repeat(64)).update();

        var replay = repository.createIntent(7L, "legacy-file-key", "private/new.png", "internal/new.png",
                image("new.png", 512, 1200, 900), EXPIRES_AT);

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.objectKey()).isEqualTo("private/original.png");
        assertThat(count("media_asset")).isEqualTo(1);
    }

    @Test
    void uploadCompletionBindsActualSizeAndCannotMoveAcrossAssets() {
        var first = repository.createIntent(7L, "first-intent", "private/first.png", "internal/first.png",
                image("first.png", 128, 640, 480), EXPIRES_AT);
        var second = repository.createIntent(7L, "second-intent", "private/second.png", "internal/second.png",
                image("second.png", 256, 800, 600), EXPIRES_AT);

        var completed = repository.complete(7L, first.id(), "complete-key", 128);
        var replay = repository.complete(7L, first.id(), "complete-key", 128);

        assertThat(completed.mediaId()).isEqualTo(first.id());
        assertThat(replay.mediaId()).isEqualTo(first.id());
        assertThat(uploadStatus(first.id())).isEqualTo("COMPLETED");
        assertConflict(() -> repository.complete(7L, first.id(), "complete-key", 127));
        assertConflict(() -> repository.complete(7L, second.id(), "complete-key", 256));
        assertThat(uploadStatus(second.id())).isEqualTo("UPLOADING");
    }

    @Test
    void missingAssetOrDatabaseFailureRollsBackTheRequestKey() {
        assertThatThrownBy(() -> repository.complete(7L, 999L, "missing-complete", 64))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(idempotencyCount("missing-complete")).isZero();

        insertAsset(999L, 7L, "private/repaired.png", 64);
        repository.complete(7L, 999L, "missing-complete", 64);
        assertThat(uploadStatus(999L)).isEqualTo("COMPLETED");

        jdbcClient.sql("DROP TABLE media_asset").update();
        assertThatThrownBy(() -> repository.createIntent(7L, "database-failure", "private/failure.png",
                "internal/failure.png", image("failure.png", 64, 32, 32), EXPIRES_AT))
                .isInstanceOf(DataAccessException.class);
        assertThat(idempotencyCount("database-failure")).isZero();
    }

    private UploadIntentRequest image(String filename, long size, int width, int height) {
        return new UploadIntentRequest(filename, "image/png", size, width, height, null);
    }

    private void createMediaTable() {
        jdbcClient.sql("""
                CREATE TABLE media_asset (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    uploader_id BIGINT NULL,
                    object_key VARCHAR(512) NOT NULL UNIQUE,
                    url VARCHAR(1024) NOT NULL,
                    content_type VARCHAR(128) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    expected_size BIGINT NULL,
                    width INT NULL,
                    height INT NULL,
                    duration_seconds INT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
                    upload_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
                    upload_expires_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
    }

    private void insertAsset(long id, long uploaderId, String objectKey, long expectedSize) {
        jdbcClient.sql("""
                        INSERT INTO media_asset(
                            id, uploader_id, object_key, url, content_type, size_bytes, expected_size,
                            status, upload_status, upload_expires_at)
                        VALUES (:id, :uploaderId, :objectKey, :url, 'image/png', :size, :size,
                                'PENDING_REVIEW', 'UPLOADING', :expiresAt)
                        """)
                .param("id", id).param("uploaderId", uploaderId).param("objectKey", objectKey)
                .param("url", "internal/" + objectKey).param("size", expectedSize)
                .param("expiresAt", EXPIRES_AT).update();
    }

    private String uploadStatus(long mediaId) {
        return jdbcClient.sql("SELECT upload_status FROM media_asset WHERE id = :id")
                .param("id", mediaId).query(String.class).single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long idempotencyCount(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM file_idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private void assertConflict(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DUPLICATE_OPERATION));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:file-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(JdbcTemplate jdbcTemplate) {
            return JdbcClient.create(jdbcTemplate);
        }

        @Bean
        MediaRepository mediaRepository(JdbcTemplate jdbcTemplate) {
            return new MediaRepository(jdbcTemplate);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
