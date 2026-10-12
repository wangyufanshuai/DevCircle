package io.github.devcircle.content;

import io.github.devcircle.content.application.DeductionService;
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

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeductionConcurrencyIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content").withUsername("devcircle").withPassword("devcircle");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired JdbcClient jdbc;
    @Autowired DeductionService service;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM deduction_idempotency WHERE post_id = 99001").update();
        jdbc.sql("DELETE FROM deduction_attempt WHERE post_id = 99001").update();
        jdbc.sql("DELETE FROM deduction_score WHERE user_id = 99002").update();
        jdbc.sql("DELETE FROM post_content WHERE post_id = 99001").update();
        jdbc.sql("DELETE FROM post WHERE id = 99001").update();
        jdbc.sql("INSERT INTO post(id, author_id, title, status, theme, solvable) VALUES (99001, 7, 'Concurrency case', 'PUBLISHED', 'DEDUCTION', TRUE)").update();
        jdbc.sql("INSERT INTO post_content(post_id, markdown, answer_keywords, solution) VALUES (99001, 'Clue', '[\"clock\"]', 'The clock changed')").update();
    }

    @Test
    void simultaneousSameKeyAnswersCommitOneAttemptAndOneAward() throws Exception {
        race(8, i -> service.submitAnswer(99002L, 99001L, "clock", "same-key"));
        assertAward(20, 1);
    }

    @Test
    void simultaneousDifferentKeysStillCommitOneAward() throws Exception {
        race(8, i -> service.submitAnswer(99002L, 99001L, "clock", "key-" + i));
        assertAward(20, 1);
    }

    @Test
    void concurrentWrongAnswerAndRevealCannotLoseTheReveal() throws Exception {
        race(2, i -> i == 0 ? service.submitAnswer(99002L, 99001L, "wrong", "wrong-key")
                : service.reveal(99002L, 99001L, "reveal-key"));
        assertAward(0, 0);
        assertThat(jdbc.sql("SELECT revealed FROM deduction_attempt WHERE post_id=99001 AND user_id=99002")
                .query(Boolean.class).single()).isTrue();
    }

    private void assertAward(long points, long solved) {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM deduction_attempt WHERE post_id=99001 AND user_id=99002")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT SUM(points) FROM deduction_attempt WHERE post_id=99001 AND user_id=99002")
                .query(Long.class).single()).isEqualTo(points);
        assertThat(jdbc.sql("SELECT solved_count FROM post WHERE id=99001").query(Long.class).single()).isEqualTo(solved);
        assertThat(jdbc.sql("SELECT COALESCE(SUM(total_points),0) FROM deduction_score WHERE user_id=99002")
                .query(Long.class).single()).isEqualTo(points);
    }

    private void race(int count, java.util.function.IntFunction<Object> operation) throws Exception {
        var barrier = new CyclicBarrier(count);
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<Object>>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit((Callable<Object>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return operation.apply(index);
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }
}
