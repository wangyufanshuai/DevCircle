package io.github.devcircle.content.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeductionServiceStateTest {
    private JdbcClient jdbcClient;
    private DeductionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:deduction-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE post (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(32) NOT NULL,
                    solvable BOOLEAN NOT NULL,
                    solved_count BIGINT NOT NULL DEFAULT 0)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE post_content (
                    post_id BIGINT PRIMARY KEY,
                    answer_keywords VARCHAR(2000),
                    solution VARCHAR(2000))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE deduction_attempt (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    answer VARCHAR(2000) NOT NULL,
                    correct BOOLEAN NOT NULL,
                    revealed BOOLEAN NOT NULL DEFAULT FALSE,
                    points INT NOT NULL DEFAULT 0,
                    revealed_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(user_id, post_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE deduction_score (
                    user_id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    correct_count BIGINT NOT NULL DEFAULT 0,
                    total_points BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE deduction_idempotency (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
                    user_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL,
                    operation VARCHAR(32) NOT NULL)
                """).update();
        seedPost(1001L, "[\"clock\",\"hidden layer\"]", "The clock was hidden in a false layer.");
        seedPost(1002L, "[\"mirror\",\"reflection\"]", "The mirror revealed the route.");
        seedPost(1003L, null, null);
        seedPost(1004L, "[\"draft\"]", "Not public.", "PENDING", true);
        seedPost(1005L, "[\"essay\"]", "Not a case.", "PUBLISHED", false);
        seedPost(1006L, "[\"夹层\",\"调换时钟\",\"伪造脚印\",\"镜面反射\",\"潮汐延迟\","
                + "\"证词串供\",\"密码替换\",\"灯塔盲区\",\"列车换厢\",\"档案重排\"]", "同义词规则成立。");
        seedPost(1007L, "[\"   \"]", "Blank keywords must not match.");
        service = new DeductionService(jdbcClient);
    }

    @Test
    void correctAnswerAwardsPointsExactlyOnceAndUnlocksTheSolution() {
        var first = service.submitAnswer(7L, 1001L,
                "CLOCK — hidden layer", "answer-correct-1");

        assertThat(first.correct()).isTrue();
        assertThat(first.duplicated()).isFalse();
        assertThat(first.revealed()).isFalse();
        assertThat(first.solutionUnlocked()).isTrue();
        assertThat(first.points()).isEqualTo(20);
        assertThat(first.solution()).isEqualTo("The clock was hidden in a false layer.");

        var sameRequest = service.submitAnswer(7L, 1001L,
                "ignored replay", "answer-correct-1");
        var newKeyForSameAttempt = service.submitAnswer(7L, 1001L,
                "clock hidden layer", "answer-correct-2");

        assertThat(sameRequest.duplicated()).isTrue();
        assertThat(newKeyForSameAttempt.duplicated()).isTrue();
        assertThat(newKeyForSameAttempt.correct()).isTrue();
        assertThat(newKeyForSameAttempt.points()).isEqualTo(20);
        assertThat(value("SELECT solved_count FROM post WHERE id = 1001")).isEqualTo(1L);
        assertThat(value("SELECT correct_count FROM deduction_score WHERE user_id = 7")).isEqualTo(1L);
        assertThat(value("SELECT total_points FROM deduction_score WHERE user_id = 7")).isEqualTo(20L);
        assertThat(value("SELECT COUNT(*) FROM deduction_attempt WHERE user_id = 7 AND post_id = 1001"))
                .isEqualTo(1L);
    }

    @Test
    void revealingAWrongAttemptPermanentlyPreventsLaterPoints() {
        var wrong = service.submitAnswer(8L, 1001L, "the gardener", "answer-wrong-1");
        assertThat(wrong.correct()).isFalse();
        assertThat(wrong.solutionUnlocked()).isFalse();
        assertThat(wrong.solution()).isNull();

        var revealed = service.reveal(8L, 1001L, "reveal-wrong-1");
        assertThat(revealed.correct()).isFalse();
        assertThat(revealed.revealed()).isTrue();
        assertThat(revealed.solutionUnlocked()).isTrue();
        assertThat(revealed.points()).isZero();
        assertThat(revealed.solution()).isEqualTo("The clock was hidden in a false layer.");

        var laterCorrectText = service.submitAnswer(8L, 1001L,
                "clock hidden layer", "answer-after-reveal");
        assertThat(laterCorrectText.duplicated()).isTrue();
        assertThat(laterCorrectText.correct()).isFalse();
        assertThat(laterCorrectText.revealed()).isTrue();
        assertThat(laterCorrectText.points()).isZero();
        assertThat(value("SELECT solved_count FROM post WHERE id = 1001")).isZero();
        assertThat(value("SELECT COUNT(*) FROM deduction_score WHERE user_id = 8")).isZero();
    }

    @Test
    void revealWithoutAttemptCreatesAStableZeroPointAttempt() {
        var first = service.reveal(9L, 1002L, "reveal-first-1");
        var replay = service.reveal(9L, 1002L, "reveal-first-1");

        assertThat(first.revealed()).isTrue();
        assertThat(first.duplicated()).isFalse();
        assertThat(first.solutionUnlocked()).isTrue();
        assertThat(first.points()).isZero();
        assertThat(replay.duplicated()).isTrue();
        assertThat(value("SELECT COUNT(*) FROM deduction_attempt WHERE user_id = 9 AND post_id = 1002"))
                .isEqualTo(1L);
    }

    @Test
    void revealingAnAlreadySolvedCaseDoesNotErasePoints() {
        service.submitAnswer(10L, 1002L, "mirror reflection", "solved-before-reveal");

        var result = service.reveal(10L, 1002L, "reveal-after-solved");

        assertThat(result.correct()).isTrue();
        assertThat(result.revealed()).isFalse();
        assertThat(result.points()).isEqualTo(20);
        assertThat(result.solutionUnlocked()).isTrue();
        assertThat(value("SELECT total_points FROM deduction_score WHERE user_id = 10")).isEqualTo(20L);
    }

    @Test
    void idempotencyKeyCannotMoveAcrossOperationsUsersOrPosts() {
        service.submitAnswer(11L, 1003L, "anything", "bound-key");

        assertConflict(() -> service.reveal(11L, 1003L, "bound-key"));
        assertConflict(() -> service.submitAnswer(12L, 1003L, "anything", "bound-key"));
        assertConflict(() -> service.submitAnswer(11L, 1002L, "anything", "bound-key"));
    }

    @Test
    void requestValidationRejectsMissingIdentityAndInvalidKeysBeforeDatabaseMutation() {
        assertCode(() -> service.submitAnswer(null, 1001L, "answer", "key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.submitAnswer(0L, 1001L, "answer", "key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.submitAnswer(7L, null, "answer", "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.submitAnswer(7L, 0L, "answer", "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.submitAnswer(7L, 1001L, null, "null-answer"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.submitAnswer(7L, 1001L, "   ", "blank-answer"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.submitAnswer(7L, 1001L, "answer", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.submitAnswer(7L, 1001L, "answer", " "), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.submitAnswer(7L, 1001L, "answer", "x".repeat(129)),
                ErrorCode.VALIDATION_FAILED);
        assertThat(value("SELECT COUNT(*) FROM deduction_idempotency")).isZero();
    }

    @Test
    void unpublishedUnsolvableAndMissingContentCannotCreateDeductionState() {
        assertCode(() -> service.submitAnswer(22L, 1004L, "draft", "pending-answer"),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.reveal(22L, 1005L, "essay-reveal"), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.submitAnswer(22L, 9999L, "missing", "missing-answer"),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.myAttempt(22L, 1004L), ErrorCode.RESOURCE_NOT_FOUND);

        assertThat(value("SELECT COUNT(*) FROM deduction_idempotency")).isZero();
        assertThat(value("SELECT COUNT(*) FROM deduction_attempt")).isZero();
    }

    @Test
    void attemptsStatsAndLeaderboardReflectOnlyPersistedState() {
        var none = service.myAttempt(20L, 1001L);
        assertThat(none.correct()).isFalse();
        assertThat(none.revealed()).isFalse();
        assertThat(none.solutionUnlocked()).isFalse();
        assertThat(none.solution()).isNull();

        service.submitAnswer(20L, 1001L, "clock hidden layer", "stats-correct");
        service.reveal(20L, 1002L, "stats-revealed");
        var persisted = service.myAttempt(20L, 1001L);
        var stats = service.stats(20L);

        assertThat(persisted.correct()).isTrue();
        assertThat(persisted.duplicated()).isFalse();
        assertThat(persisted.points()).isEqualTo(20);
        assertThat(stats.attempts()).isEqualTo(2);
        assertThat(stats.correctCount()).isEqualTo(1);
        assertThat(stats.revealedCount()).isEqualTo(1);
        assertThat(stats.totalPoints()).isEqualTo(20);
        assertThat(service.leaderboard()).singleElement().satisfies(entry -> {
            assertThat(entry.userId()).isEqualTo(20L);
            assertThat(entry.correctCount()).isEqualTo(1);
            assertThat(entry.totalPoints()).isEqualTo(20);
        });
    }

    @Test
    void missingKeywordsCannotUnlockANullSolution() {
        var result = service.submitAnswer(21L, 1003L, "anything", "missing-keywords");

        assertThat(result.correct()).isFalse();
        assertThat(result.solutionUnlocked()).isFalse();
        assertThat(result.solution()).isNull();

        var revealed = service.reveal(21L, 1003L, "missing-solution-reveal");
        assertThat(revealed.solutionUnlocked()).isTrue();
        assertThat(revealed.solution()).isEmpty();
    }

    @Test
    void chineseSynonymFamiliesAreExpandedAndBlankKeywordsNeverMatch() {
        var synonym = service.submitAnswer(23L, 1006L, "凶手藏在钟楼暗层", "synonym-answer");
        var blankKeyword = service.submitAnswer(24L, 1007L, "anything", "blank-keyword-answer");

        assertThat(synonym.correct()).isTrue();
        assertThat(synonym.solution()).isEqualTo("同义词规则成立。");
        assertThat(blankKeyword.correct()).isFalse();
        assertThat(blankKeyword.solutionUnlocked()).isFalse();
    }

    private void seedPost(long id, String keywords, String solution) {
        seedPost(id, keywords, solution, "PUBLISHED", true);
    }

    private void seedPost(long id, String keywords, String solution, String status, boolean solvable) {
        jdbcClient.sql("""
                        INSERT INTO post(id, status, solvable, solved_count)
                        VALUES (:id, :status, :solvable, 0)
                        """)
                .param("id", id).param("status", status).param("solvable", solvable).update();
        jdbcClient.sql("INSERT INTO post_content(post_id, answer_keywords, solution) VALUES (:id, :keywords, :solution)")
                .param("id", id).param("keywords", keywords).param("solution", solution).update();
    }

    private long value(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private void assertConflict(ThrowingAction action) {
        assertCode(action, ErrorCode.DUPLICATE_OPERATION);
    }

    private void assertCode(ThrowingAction action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
