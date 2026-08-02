package io.github.devcircle.content;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DraftPersistenceIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    PostRepository postRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void draftRoundTripPersistsContentAndRejectsAStaleVersion() {
        var created = postRepository.createDraft(42L, draft("钟楼记录", "原始正文", null), "create-draft");

        assertThat(created.id()).isPositive();
        assertThat(created.version()).isZero();
        assertThat(postRepository.getDraft(42L, created.id()).content()).isEqualTo("原始正文");

        var updated = postRepository.updateDraft(
                42L, created.id(), draft("钟楼记录", "服务端新正文", 0L), "update-draft");

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.content()).isEqualTo("服务端新正文");
        assertThatThrownBy(() -> postRepository.updateDraft(
                42L, created.id(), draft("钟楼记录", "过期覆盖", 0L), "stale-update"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        assertThat(postRepository.getDraft(42L, created.id()).content()).isEqualTo("服务端新正文");
    }

    @Test
    void recommendationWriteKeysAreBoundToRequestFingerprints() {
        String fingerprint = "a".repeat(64);
        jdbcClient.sql("""
                        INSERT INTO recommend_write_idempotency(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES ('migration-probe', 42, 'EXPOSURE', :fingerprint)
                        """)
                .param("fingerprint", fingerprint)
                .update();

        assertThat(jdbcClient.sql("""
                        SELECT request_fingerprint
                        FROM recommend_write_idempotency
                        WHERE idempotency_key = 'migration-probe'
                        """)
                .query(String.class)
                .single()).isEqualTo(fingerprint);
    }

    @Test
    void contentWriteKeysAreBoundToRequestFingerprints() {
        String fingerprint = "b".repeat(64);
        jdbcClient.sql("""
                        INSERT INTO content_idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES ('content-migration-probe', 42, 'CREATE_DRAFT', :fingerprint)
                        """)
                .param("fingerprint", fingerprint)
                .update();

        assertThat(jdbcClient.sql("""
                        SELECT request_fingerprint
                        FROM content_idempotency_record
                        WHERE idempotency_key = 'content-migration-probe'
                        """)
                .query(String.class)
                .single()).isEqualTo(fingerprint);
    }

    @Test
    void readerWriteKeysAreBoundToRequestFingerprints() {
        String deductionFingerprint = "c".repeat(64);
        String progressFingerprint = "d".repeat(64);
        jdbcClient.sql("""
                        INSERT INTO deduction_idempotency(
                            idempotency_key, user_id, post_id, operation, request_fingerprint)
                        VALUES ('deduction-migration-probe', 42, 1, 'ANSWER', :fingerprint)
                        """)
                .param("fingerprint", deductionFingerprint).update();
        jdbcClient.sql("""
                        INSERT INTO reading_progress_idempotency(
                            idempotency_key, user_id, post_id, request_fingerprint)
                        VALUES ('progress-migration-probe', 42, 1, :fingerprint)
                        """)
                .param("fingerprint", progressFingerprint).update();

        assertThat(jdbcClient.sql("""
                        SELECT request_fingerprint FROM deduction_idempotency
                        WHERE idempotency_key = 'deduction-migration-probe'
                        """).query(String.class).single()).isEqualTo(deductionFingerprint);
        assertThat(jdbcClient.sql("""
                        SELECT request_fingerprint FROM reading_progress_idempotency
                        WHERE idempotency_key = 'progress-migration-probe'
                        """).query(String.class).single()).isEqualTo(progressFingerprint);
    }

    @Test
    void submittedDraftBecomesPendingAndRemainsUnavailableToPublicReaders() {
        var created = postRepository.createDraft(43L, draft("未公开的钟楼答案", "只有审核通过后才能看到。", null),
                "create-pending-draft");

        var submitted = postRepository.submitDraft(43L, created.id(), created.version(), "submit-pending-draft");

        assertThat(submitted.status()).isEqualTo("PENDING");
        assertThat(submitted.version()).isEqualTo(1);
        assertThat(postRepository.submitDraft(43L, created.id(), created.version(), "submit-pending-draft"))
                .extracting("id", "status", "version")
                .containsExactly(created.id(), "PENDING", 1L);
        assertThatThrownBy(() -> postRepository.publicDetail(created.id(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void mediaDraftFailsExplicitlyUntilTheUploadSchemaIsReady() {
        jdbcClient.sql("""
                        INSERT INTO media_asset(object_key, url, content_type, size_bytes, status)
                        VALUES ('pending/evidence.webp', '', 'image/webp', 128, 'PENDING_REVIEW')
                        """).update();
        Long mediaId = jdbcClient.sql("SELECT id FROM media_asset WHERE object_key = 'pending/evidence.webp'")
                .query(Long.class)
                .single();
        SaveDraftRequest request = new SaveDraftRequest(
                "媒体迁移尚未完成", "正文", List.of("推理"), "TEXT", List.of(mediaId),
                List.of(new DraftMediaInput(mediaId, "COVER", "钟楼证物", 0)),
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "", "", "", "", "", "", null);

        assertThatThrownBy(() -> postRepository.createDraft(44L, request, "media-schema-not-ready"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private SaveDraftRequest draft(String title, String content, Long version) {
        return new SaveDraftRequest(
                title, content, List.of("推理"), "TEXT", List.of(), List.of(),
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "停摆的怀表", "守夜人", "午夜十二点", "谁改动了钟楼？",
                "守夜人", "怀表提供了七分钟时差。", version);
    }
}
