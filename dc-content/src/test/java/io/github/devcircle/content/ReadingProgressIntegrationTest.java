package io.github.devcircle.content;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
class ReadingProgressIntegrationTest {
    private static final long USER_ID = 73001L;

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
    PostRepository repository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void preparePosts() {
        jdbcClient.sql("DELETE FROM reading_progress_idempotency WHERE user_id IN (:userId, :otherUser)")
                .param("userId", USER_ID).param("otherUser", USER_ID + 1).update();
        jdbcClient.sql("DELETE FROM reading_progress WHERE user_id IN (:userId, :otherUser)")
                .param("userId", USER_ID).param("otherUser", USER_ID + 1).update();
        jdbcClient.sql("DELETE FROM deduction_attempt WHERE user_id = :userId")
                .param("userId", USER_ID).update();
        jdbcClient.sql("DELETE FROM post_content WHERE post_id BETWEEN 93001 AND 93005").update();
        jdbcClient.sql("DELETE FROM post WHERE id BETWEEN 93001 AND 93005").update();

        post(93001, "正在阅读的钟楼", "PUBLISHED", "DEDUCTION", true);
        post(93002, "已经读完的航行日志", "PUBLISHED", "SCI_FI", false);
        post(93003, "已经解开的密室", "PUBLISHED", "DEDUCTION", true);
        post(93004, "主动查看答案的档案", "PUBLISHED", "COSMOS", true);
        post(93005, "已经撤回的内容", "REJECTED", "DEDUCTION", false);
    }

    @Test
    void progressIsMonotonicIdempotentAndIncludedInPostDetail() {
        var first = repository.saveReadingProgress(USER_ID, 93001L,
                new ReadingProgressRequest(18, "reading-section-1", false), "progress-first");
        var replay = repository.saveReadingProgress(USER_ID, 93001L,
                new ReadingProgressRequest(81, "reading-section-9", false), "progress-first");
        var regressed = repository.saveReadingProgress(USER_ID, 93001L,
                new ReadingProgressRequest(9, "reading-section-0", false), "progress-regression");
        var completed = repository.saveReadingProgress(USER_ID, 93001L,
                new ReadingProgressRequest(97, "reading-section-3", true), "progress-complete");

        assertThat(first.progressPercent()).isEqualTo(18);
        assertThat(replay.progressPercent()).isEqualTo(18);
        assertThat(regressed.progressPercent()).isEqualTo(18);
        assertThat(completed.progressPercent()).isEqualTo(100);
        assertThat(completed.anchor()).isEqualTo("reading-section-3");
        assertThat(completed.completed()).isTrue();

        var viewerState = repository.publicDetail(93001L, USER_ID).viewerState();
        assertThat(viewerState.readingProgress()).isEqualTo(100);
        assertThat(viewerState.readingAnchor()).isEqualTo("reading-section-3");
        assertThat(viewerState.readingCompleted()).isTrue();

        assertThatThrownBy(() -> repository.saveReadingProgress(USER_ID + 1, 93001L,
                new ReadingProgressRequest(20, "reading-section-1", false), "progress-first"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DUPLICATE_OPERATION));
    }

    @Test
    void dashboardTrackSeparatesActiveSolvedRevealedCompletedAndUnavailableContent() {
        progress(93001, 42, false);
        progress(93002, 100, true);
        progress(93003, 76, false);
        progress(93004, 61, false);
        progress(93005, 33, false);
        deduction(93003, true, false, 20);
        deduction(93004, false, true, 0);

        Map<Long, io.github.devcircle.content.api.ReadingProgressSummary> tracks = repository
                .listReadingProgress(USER_ID, 12).stream()
                .collect(Collectors.toMap(io.github.devcircle.content.api.ReadingProgressSummary::postId,
                        Function.identity()));

        assertThat(tracks.get(93001L).trackState()).isEqualTo("IN_PROGRESS");
        assertThat(tracks.get(93002L).trackState()).isEqualTo("COMPLETED");
        assertThat(tracks.get(93003L).trackState()).isEqualTo("SOLVED");
        assertThat(tracks.get(93004L).trackState()).isEqualTo("REVEALED");
        assertThat(tracks.get(93005L).trackState()).isEqualTo("UNAVAILABLE");
        assertThat(tracks.get(93005L).available()).isFalse();
        assertThat(tracks.get(93005L).title()).isEqualTo("内容已不可用");
    }

    private void post(long id, String title, String status, String theme, boolean solvable) {
        jdbcClient.sql("""
                        INSERT INTO post(id, author_id, title, status, theme, solvable)
                        VALUES (:id, 7, :title, :status, :theme, :solvable)
                        """)
                .param("id", id).param("title", title).param("status", status)
                .param("theme", theme).param("solvable", solvable).update();
        jdbcClient.sql("INSERT INTO post_content(post_id, markdown) VALUES (:id, :markdown)")
                .param("id", id).param("markdown", "# " + title + "\n\n正文内容").update();
    }

    private void progress(long postId, int percent, boolean completed) {
        jdbcClient.sql("""
                        INSERT INTO reading_progress(user_id, post_id, progress_percent, anchor, completed)
                        VALUES (:userId, :postId, :percent, 'reading-section-1', :completed)
                        """)
                .param("userId", USER_ID).param("postId", postId).param("percent", percent)
                .param("completed", completed).update();
    }

    private void deduction(long postId, boolean correct, boolean revealed, int points) {
        jdbcClient.sql("""
                        INSERT INTO deduction_attempt(post_id, user_id, answer, correct, revealed, points)
                        VALUES (:postId, :userId, 'test', :correct, :revealed, :points)
                        """)
                .param("postId", postId).param("userId", USER_ID).param("correct", correct)
                .param("revealed", revealed).param("points", points).update();
    }
}
