package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.ValidatedEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(SearchLeaseIntegrationTest.Config.class)
class SearchLeaseIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_search").withUsername("devcircle").withPassword("devcircle");
    @Autowired JdbcClient jdbc;
    @Autowired SearchEventCoordinator coordinator;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM search_consumer_dedup").update();
        jdbc.sql("DELETE FROM search_event_watermark").update();
        jdbc.sql("UPDATE search_reindex_guard SET active_job_id=NULL").update();
        jdbc.sql("DELETE FROM search_reindex_job").update();
    }

    @Test
    void concurrentClaimsForOneEventHaveExactlyOneOwner() throws Exception {
        var event = event(91001);
        var barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<SearchEventCoordinator.ClaimResult> attempt = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try { return coordinator.claim(event, 91001); }
            catch (SearchEventCoordinator.SearchAggregateBusyException expected) { return null; }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat((a != null ? 1 : 0) + (b != null ? 1 : 0)).isEqualTo(1);
            var winner = a != null ? a : b;
            assertThat(winner.claimed()).isTrue();
            String owner = jdbc.sql("SELECT lease_owner FROM search_event_watermark WHERE post_id=91001").query(String.class).single();
            assertThat(owner).isEqualTo(winner.leaseToken());
        }
    }

    @Test
    void expiredWorkerCannotCompleteOrFailTheNewOwnersWork() {
        var event = event(91002);
        var old = coordinator.claim(event, 91002);
        jdbc.sql("UPDATE search_consumer_dedup SET updated_at=TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP)").update();
        jdbc.sql("UPDATE search_event_watermark SET lease_until=TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP)").update();
        var current = coordinator.claim(event, 91002);
        assertThat(current.claimed()).isTrue();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThatThrownBy(() -> coordinator.complete(event.eventId().toString(), 91002, event.occurredAt(), "UPSERT", old.leaseToken()))
                .isInstanceOf(SearchEventCoordinator.SearchEventLeaseLostException.class);
        assertThatThrownBy(() -> coordinator.fail(event.eventId().toString(), 91002, new IllegalStateException("late failure"), old.leaseToken()))
                .isInstanceOf(SearchEventCoordinator.SearchEventLeaseLostException.class);
        assertThat(jdbc.sql("SELECT lease_owner FROM search_event_watermark WHERE post_id=91002").query(String.class).single())
                .isEqualTo(current.leaseToken());
        coordinator.complete(event.eventId().toString(), 91002, event.occurredAt(), "UPSERT", current.leaseToken());
        assertThat(jdbc.sql("SELECT status FROM search_consumer_dedup").query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void olderEventCannotMoveACompletedWatermarkBackwards() {
        var current = event(91003);
        var claim = coordinator.claim(current, 91003);
        coordinator.complete(current.eventId().toString(), 91003, current.occurredAt(), "DELETE", claim.leaseToken());
        var older = new ValidatedEvent(UUID.randomUUID(), "post.updated", 1, current.occurredAt().minusSeconds(1),
                "dc-content", "91003", current.payload());
        assertThat(coordinator.claim(older, 91003).claimed()).isFalse();
        assertThat(jdbc.sql("SELECT last_action FROM search_event_watermark WHERE post_id=91003")
                .query(String.class).single()).isEqualTo("DELETE");
        assertThat(jdbc.sql("SELECT last_event_id FROM search_event_watermark WHERE post_id=91003")
                .query(String.class).single()).isEqualTo(current.eventId().toString());
    }

    @Test
    void eventIdCannotBeReboundToAnotherPayload() {
        var original = event(91004);
        var owner = coordinator.claim(original, 91004);
        var forged = new ValidatedEvent(original.eventId(), original.type(), 1, original.occurredAt(),
                "dc-content", "91004", new ObjectMapper().createObjectNode().put("postId", 91004).put("title", "changed"));
        assertThatThrownBy(() -> coordinator.claim(forged, 91004)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT lease_owner FROM search_event_watermark WHERE post_id=91004")
                .query(String.class).single()).isEqualTo(owner.leaseToken());
    }

    @Test
    void realMysqlLocksExcludeConcurrentBatchAndCancellation() throws Exception {
        var source = mock(SearchIndexService.class);
        var projection = mock(SearchProjectionStore.class);
        var jobs = new SearchReindexJobs(jdbc, manager, source, projection, new ObjectMapper());
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(source.loadContentBatch("")).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return new SearchIndexService.ContentBatch(List.of(), "", false);
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var running = worker.submit(() -> jobs.step(id, 0));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> jobs.step(id, 0)).isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).as("translated cause: %s", ex.getCause()).isEqualTo(ErrorCode.VERSION_CONFLICT));
                assertThatThrownBy(() -> jobs.cancel(id)).isInstanceOf(BusinessException.class);
            } finally { release.countDown(); }
            var checkpoint = running.get(10, TimeUnit.SECONDS);
            assertThat(checkpoint.revision()).isEqualTo(1);
            assertThat(jobs.step(id, 0)).isEqualTo(checkpoint);
        }
    }

    @Test
    void mysqlCheckpointRollsBackAfterExternalWriteFailureAndCanResume() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source = spy(new SearchIndexService(mock(ElasticsearchOperations.class), mapper, "http://127.0.0.1:1", coordinator));
        var projection = mock(SearchProjectionStore.class);
        var post = new PostSummary(1L, "TEXT", "one", "body", "author", List.of(), List.of(), 0, 0, 0,
                "PUBLISHED", Instant.EPOCH).withAggregateVersion(1L);
        doReturn(new SearchIndexService.ContentBatch(List.of(post), "", false)).when(source).loadContentBatch("");
        doThrow(new IllegalStateException("connection lost after ES write")).doNothing().when(projection).upsert(any());
        var jobs = new SearchReindexJobs(jdbc, manager, source, projection, mapper);
        UUID id = UUID.randomUUID();
        var initial = jobs.start(id, true);
        assertThatThrownBy(() -> jobs.step(id, 0)).isInstanceOf(BusinessException.class);
        var restarted = new SearchReindexJobs(jdbc, manager, source, projection, mapper);
        assertThat(restarted.get(id)).isEqualTo(initial);
        assertThat(restarted.step(id, 0).phase()).isEqualTo("VERIFYING");
        verify(projection, times(2)).upsert(any());
    }

    private ValidatedEvent event(long postId) {
        return new ValidatedEvent(UUID.randomUUID(), "post.updated", 1, Instant.parse("2026-10-12T00:00:00Z"),
                "dc-content", Long.toString(postId), new ObjectMapper().createObjectNode().put("postId", postId).put("aggregateVersion", 1));
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            return source;
        }
        @Bean JdbcClient jdbcClient(DataSource source) { return JdbcClient.create(source); }
        @Bean SearchEventCoordinator coordinator(JdbcClient jdbc) { return new SearchEventCoordinator(jdbc); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
}
