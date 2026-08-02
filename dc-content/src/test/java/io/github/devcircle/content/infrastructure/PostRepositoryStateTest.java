package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.api.SaveDraftRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(PostRepositoryStateTest.TestConfig.class)
class PostRepositoryStateTest {
    @Autowired
    @Qualifier("normalRepository")
    private PostRepository repository;
    @Autowired
    @Qualifier("failingRepository")
    private PostRepository failingRepository;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE post (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    author_id BIGINT NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
                    theme VARCHAR(32) NOT NULL DEFAULT 'DEDUCTION',
                    content_kind VARCHAR(48) NOT NULL DEFAULT 'CASE_FILE',
                    difficulty VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
                    solvable BOOLEAN NOT NULL DEFAULT FALSE,
                    solved_count BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
                    version BIGINT NOT NULL DEFAULT 0,
                    last_saved_at TIMESTAMP NULL,
                    submitted_at TIMESTAMP NULL,
                    review_reason VARCHAR(512) NULL,
                    like_count BIGINT NOT NULL DEFAULT 0,
                    comment_count BIGINT NOT NULL DEFAULT 0,
                    view_count BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE post_content (
                    post_id BIGINT PRIMARY KEY,
                    markdown CLOB NOT NULL,
                    clues JSON NULL,
                    suspects JSON NULL,
                    timeline JSON NULL,
                    question VARCHAR(512) NULL,
                    answer_keywords JSON NULL,
                    solution CLOB NULL,
                    explanation CLOB NULL,
                    worldbuilding JSON NULL,
                    cosmic_facts JSON NULL,
                    related_post_ids JSON NULL)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE tag (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL UNIQUE,
                    use_count BIGINT NOT NULL DEFAULT 0)
                """).update();
        jdbcClient.sql("CREATE TABLE post_tag(post_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, PRIMARY KEY(post_id, tag_id))")
                .update();
        jdbcClient.sql("""
                CREATE TABLE media_asset (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    uploader_id BIGINT NULL,
                    object_key VARCHAR(512) NOT NULL UNIQUE,
                    url VARCHAR(1024) NOT NULL DEFAULT '',
                    content_type VARCHAR(128) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    width INT NULL,
                    height INT NULL,
                    duration_seconds INT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
                    upload_status VARCHAR(32) NOT NULL DEFAULT 'PENDING')
                """).update();
        jdbcClient.sql("""
                CREATE TABLE post_media (
                    post_id BIGINT NOT NULL,
                    media_id BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL DEFAULT 'INLINE',
                    alt_text VARCHAR(240) NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    PRIMARY KEY(post_id, media_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE content_idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(48) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    resource_id BIGINT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE reading_progress (
                    user_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL,
                    progress_percent INT NOT NULL DEFAULT 0,
                    anchor VARCHAR(160) NULL,
                    completed BOOLEAN NOT NULL DEFAULT FALSE,
                    last_read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(user_id, post_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE reading_progress_idempotency (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE deduction_attempt (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    answer CLOB NOT NULL,
                    correct BOOLEAN NOT NULL,
                    revealed BOOLEAN NOT NULL DEFAULT FALSE,
                    points INT NOT NULL DEFAULT 0,
                    UNIQUE(user_id, post_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE post_audit_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    auditor_id BIGINT NULL,
                    action VARCHAR(48) NOT NULL,
                    reason VARCHAR(512) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE moderation_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    author_id BIGINT NOT NULL,
                    queue_name VARCHAR(64) NOT NULL,
                    machine_result VARCHAR(64) NOT NULL,
                    risk_level VARCHAR(32) NOT NULL,
                    risk_score DOUBLE NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    review_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                    sla_deadline TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id CHAR(36) NOT NULL UNIQUE,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    payload JSON NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
    }

    @Test
    void repeatedDraftSavesKeepTagUsageEqualToRealRelations() {
        SaveDraftRequest initial = draft(Arrays.asList("推理", " 推理 ", "线索", null), null);
        var created = repository.createDraft(7L, initial, "create-tags");

        assertThat(tagCounts()).containsEntry("推理", 1L).containsEntry("线索", 1L);
        assertThat(relationCount(created.id())).isEqualTo(2);

        var saved = repository.updateDraft(7L, created.id(), draft(List.of("推理", "线索"), 0L), "save-tags");
        assertThat(saved.version()).isEqualTo(1);
        assertThat(tagCounts()).containsEntry("推理", 1L).containsEntry("线索", 1L);

        var replaced = repository.updateDraft(7L, created.id(), draft(List.of("线索", "宇宙"), 1L), "replace-tags");
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(tagCounts()).containsEntry("推理", 0L).containsEntry("线索", 1L).containsEntry("宇宙", 1L);
        assertThat(relationCount(created.id())).isEqualTo(2);
    }

    @Test
    void staleOrWrongOwnerUpdateRollsBackTheKeyAndCanBeRetried() {
        var created = repository.createDraft(7L, draft(List.of("推理"), null), "create-locking");
        var updated = repository.updateDraft(7L, created.id(), draft(List.of("推理"), 0L), "server-update");

        assertCode(() -> repository.updateDraft(7L, created.id(), draft(List.of("旧版本"), 0L), "retry-key"),
                ErrorCode.VERSION_CONFLICT);
        assertThat(idempotencyCount("retry-key")).isZero();
        assertCode(() -> repository.updateDraft(8L, created.id(), draft(List.of("越权"), 1L), "retry-key"),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(idempotencyCount("retry-key")).isZero();

        var retried = repository.updateDraft(7L, created.id(), draft(List.of("最终版本"), updated.version()), "retry-key");
        assertThat(retried.version()).isEqualTo(2);
        assertThat(retried.tags()).containsExactly("最终版本");
    }

    @Test
    void idempotencyKeyCannotReplayADifferentDraftPayload() {
        SaveDraftRequest original = draft(List.of("推理"), null);
        var created = repository.createDraft(7L, original, "payload-bound-create");
        var replay = repository.createDraft(7L, original, "payload-bound-create");

        assertThat(replay.id()).isEqualTo(created.id());
        assertCode(() -> repository.createDraft(7L, draft(List.of("宇宙"), null), "payload-bound-create"),
                ErrorCode.DUPLICATE_OPERATION);
        assertThat(count("post")).isEqualTo(1);
        assertThat(repository.getDraft(7L, created.id()).tags()).containsExactly("推理");

        SaveDraftRequest update = draft(List.of("线索"), 0L);
        var saved = repository.updateDraft(7L, created.id(), update, "payload-bound-update");
        assertThat(repository.updateDraft(7L, created.id(), update, "payload-bound-update").version())
                .isEqualTo(saved.version());
        assertCode(() -> repository.updateDraft(7L, created.id(), draft(List.of("篡改"), 0L),
                "payload-bound-update"), ErrorCode.DUPLICATE_OPERATION);
        assertThat(repository.getDraft(7L, created.id()).tags()).containsExactly("线索");

        var submitted = repository.submitDraft(7L, created.id(), saved.version(), "payload-bound-submit");
        assertThat(repository.submitDraft(7L, created.id(), saved.version(), "payload-bound-submit").status())
                .isEqualTo(submitted.status());
        assertCode(() -> repository.submitDraft(7L, created.id(), submitted.version(), "payload-bound-submit"),
                ErrorCode.DUPLICATE_OPERATION);
    }

    @Test
    void legacyFingerprintKeepsSameOperationReplayCompatibleForOneVersion() {
        var created = repository.createDraft(7L, draft(List.of("旧请求"), null), "legacy-content-key");
        jdbcClient.sql("""
                        UPDATE content_idempotency_record
                        SET request_fingerprint = :legacy
                        WHERE idempotency_key = 'legacy-content-key'
                        """)
                .param("legacy", "0".repeat(64)).update();

        var replay = repository.createDraft(7L, draft(List.of("升级后的请求"), null), "legacy-content-key");

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.tags()).containsExactly("旧请求");
        assertThat(count("post")).isEqualTo(1);
    }

    @Test
    void publicSummaryNeverReturnsDraftOrPendingContent() {
        long published = post(7L, "已发布档案", "PUBLISHED");
        long draft = post(7L, "作者草稿", "DRAFT");
        long pending = post(8L, "待审核档案", "PENDING");

        assertThat(repository.publicSummaries(List.of(pending, published, draft, published)))
                .extracting(item -> item.id())
                .containsExactly(published);
        assertThat(repository.publicSummaries(Arrays.asList(null, -1L, 0L))).isEmpty();
    }

    @Test
    void draftMediaMustBeCompletedAndOwnedByTheAuthor() {
        long owned = media(7L, "owned.webp", "COMPLETED");
        long foreign = media(8L, "foreign.webp", "COMPLETED");
        long pending = media(7L, "pending.webp", "PENDING");

        var created = repository.createDraft(7L, draftWithMedia(owned, "owned media"), "owned-media");
        assertThat(created.media()).singleElement().satisfies(item -> {
            assertThat(item.mediaId()).isEqualTo(owned);
            assertThat(item.role()).isEqualTo("COVER");
            assertThat(item.altText()).isEqualTo("owned media");
        });

        assertCode(() -> repository.createDraft(7L, draftWithMedia(foreign, "foreign media"), "foreign-media"),
                ErrorCode.UPLOAD_REJECTED);
        assertThat(idempotencyCount("foreign-media")).isZero();
        assertCode(() -> repository.createDraft(7L, draftWithMedia(pending, "pending media"), "pending-media"),
                ErrorCode.UPLOAD_REJECTED);
        assertThat(idempotencyCount("pending-media")).isZero();
    }

    @Test
    void readingProgressIsMonotonicAndRequestKeyCannotCrossReaders() {
        long postId = post(7L, "阅读中的档案", "PUBLISHED");

        var first = repository.saveReadingProgress(11L, postId,
                new ReadingProgressRequest(25, "reading-section-1", false), "progress-key");
        var replay = repository.saveReadingProgress(11L, postId,
                new ReadingProgressRequest(80, "reading-section-8", false), "progress-key");
        var regression = repository.saveReadingProgress(11L, postId,
                new ReadingProgressRequest(10, "reading-section-0", false), "progress-regression");

        assertThat(first.progressPercent()).isEqualTo(25);
        assertThat(replay.progressPercent()).isEqualTo(25);
        assertThat(regression.progressPercent()).isEqualTo(25);
        assertThat(regression.anchor()).isEqualTo("reading-section-1");
        assertCode(() -> repository.saveReadingProgress(12L, postId,
                new ReadingProgressRequest(30, null, false), "progress-key"), ErrorCode.DUPLICATE_OPERATION);

        long unavailable = post(7L, "未发布档案", "PENDING");
        assertCode(() -> repository.saveReadingProgress(11L, unavailable,
                new ReadingProgressRequest(30, null, false), "unavailable-key"), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(progressIdempotencyCount("unavailable-key")).isZero();
    }

    @Test
    void cursorTypeFilterIsAppliedBeforePagination() {
        post(7L, "普通文本", "PUBLISHED", "TEXT");
        long firstImage = post(7L, "第一张索引图", "PUBLISHED", "IMAGE");
        long secondImage = post(7L, "第二张索引图", "PUBLISHED", "IMAGE");

        var first = repository.listCursor(null, 1, "IMAGE", null, null, null);
        var second = repository.listCursor(first.nextCursor(), 1, "IMAGE", null, null, null);

        assertThat(first.records()).singleElement().satisfies(post -> {
            assertThat(post.id()).isEqualTo(secondImage);
            assertThat(post.type()).isEqualTo("IMAGE");
        });
        assertThat(first.hasMore()).isTrue();
        assertThat(second.records()).singleElement().satisfies(post -> {
            assertThat(post.id()).isEqualTo(firstImage);
            assertThat(post.type()).isEqualTo("IMAGE");
        });
        assertThat(second.hasMore()).isFalse();
        assertCode(() -> repository.listCursor(null, 20, "AUDIO", null, null, null),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void structuredContentSerializationFailureRollsBackDraftAndRequestKey() {
        assertThatThrownBy(() -> failingRepository.createDraft(7L, draft(List.of("推理"), null),
                "serialization-retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize structured draft content")
                .hasCauseInstanceOf(JsonProcessingException.class);

        assertThat(count("post")).isZero();
        assertThat(count("post_content")).isZero();
        assertThat(idempotencyCount("serialization-retry")).isZero();

        var retried = repository.createDraft(7L, draft(List.of("推理"), null), "serialization-retry");
        assertThat(retried.id()).isPositive();
        assertThat(count("post")).isEqualTo(1);
        assertThat(idempotencyCount("serialization-retry")).isEqualTo(1);
    }

    @Test
    void seedSerializationFailureRollsBackThePublishedRow() {
        assertThatThrownBy(() -> failingRepository.importSeedsIfEmpty(
                List.of(ThemeContentLibrary.posts().getFirst())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize structured seed content")
                .hasCauseInstanceOf(JsonProcessingException.class);

        assertThat(count("post")).isZero();
        assertThat(count("post_content")).isZero();
    }

    @Test
    void seedImportDatabaseFailureIsNotReportedAsZeroSuccess() {
        jdbcClient.sql("DROP TABLE post").update();

        assertThatThrownBy(() -> repository.importSeedsIfEmpty(
                List.of(ThemeContentLibrary.posts().getFirst())))
                .isInstanceOf(DataAccessException.class);
    }

    private SaveDraftRequest draft(List<String> tags, Long version) {
        return new SaveDraftRequest(
                "钟楼草稿", "# 钟楼\n\n正文", tags, "TEXT", List.of(), List.of(),
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "停摆的怀表", "守夜人", "午夜十二点", "谁改动了钟楼？",
                "守夜人", "怀表提供了七分钟时差。", version);
    }

    private SaveDraftRequest draftWithMedia(long mediaId, String altText) {
        return new SaveDraftRequest(
                "媒体草稿", "# 媒体证物", List.of("证物"), "IMAGE", List.of(mediaId),
                List.of(new DraftMediaInput(mediaId, "COVER", altText, 0)),
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "证物", "嫌疑人", "时间线", "问题", "答案", "解析", null);
    }

    private long media(long uploaderId, String objectKey, String uploadStatus) {
        jdbcClient.sql("""
                        INSERT INTO media_asset(uploader_id, object_key, content_type, size_bytes, upload_status)
                        VALUES (:uploaderId, :objectKey, 'image/webp', 128, :uploadStatus)
                        """)
                .param("uploaderId", uploaderId).param("objectKey", objectKey)
                .param("uploadStatus", uploadStatus).update();
        return jdbcClient.sql("SELECT MAX(id) FROM media_asset").query(Long.class).single();
    }

    private long post(long authorId, String title, String status) {
        return post(authorId, title, status, "TEXT");
    }

    private long post(long authorId, String title, String status, String type) {
        jdbcClient.sql("INSERT INTO post(author_id, title, status, type) VALUES (:authorId, :title, :status, :type)")
                .param("authorId", authorId).param("title", title).param("status", status)
                .param("type", type).update();
        long id = jdbcClient.sql("SELECT MAX(id) FROM post").query(Long.class).single();
        jdbcClient.sql("INSERT INTO post_content(post_id, markdown) VALUES (:id, :markdown)")
                .param("id", id).param("markdown", "# " + title).update();
        return id;
    }

    private Map<String, Long> tagCounts() {
        return jdbcClient.sql("SELECT name, use_count FROM tag ORDER BY name")
                .query((rs, rowNum) -> Map.entry(rs.getString("name"), rs.getLong("use_count")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private long relationCount(long postId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM post_tag WHERE post_id = :postId")
                .param("postId", postId).query(Long.class).single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long idempotencyCount(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM content_idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private long progressIdempotencyCount(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM reading_progress_idempotency WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
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
            dataSource.setURL("jdbc:h2:mem:content-repository-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
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

        @Bean("normalRepository")
        PostRepository postRepository(JdbcTemplate jdbcTemplate) {
            return new PostRepository(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
        }

        @Bean("failingRepository")
        PostRepository failingRepository(JdbcTemplate jdbcTemplate) {
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public String writeValueAsString(Object value) throws JsonProcessingException {
                    throw new JsonProcessingException("structured content serializer unavailable") { };
                }
            };
            return new PostRepository(jdbcTemplate, failingMapper);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
