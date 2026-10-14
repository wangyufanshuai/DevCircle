package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchReindexJobsTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final SearchProjectionStore projection = mock(SearchProjectionStore.class);
    final Map<Long, PostSearchDocument> documents = new HashMap<>();
    JdbcClient jdbc;
    DataSourceTransactionManager manager;
    SearchIndexService source;
    SearchReindexJobs jobs;

    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        manager = new DataSourceTransactionManager(ds);
        source = spy(new SearchIndexService(mock(ElasticsearchOperations.class), mapper,
                "http://127.0.0.1:1", mock(SearchEventCoordinator.class)));
        doAnswer(call -> { PostSearchDocument doc = call.getArgument(0); documents.put(doc.getId(), doc); return null; })
                .when(projection).upsert(any());
        when(projection.get(anyLong())).thenAnswer(call -> documents.get(call.getArgument(0)));
        when(projection.countsMatch(anyLong())).thenAnswer(call -> (long) documents.size() == (long) call.getArgument(0));
        jobs = newJobs();
    }

    @Test void checkpointsSurviveNewServiceAndRetryOfOldRevisionDoesNotAdvanceTwice() {
        doReturn(new SearchIndexService.ContentBatch(List.of(post(2, "two")), "next", true)).when(source).loadContentBatch("");
        doReturn(new SearchIndexService.ContentBatch(List.of(post(1, "one")), "", false)).when(source).loadContentBatch("next");
        UUID id = UUID.randomUUID();
        var created = jobs.start(id, true);
        assertThat(jobs.start(id, true)).isEqualTo(created);
        var first = jobs.step(id, 0);
        assertThat(first.copied()).isEqualTo(1);
        assertThat(first.cursor()).isEqualTo("next");
        jobs = newJobs();
        assertThat(jobs.step(id, 0)).isEqualTo(first);
        verify(source, times(1)).loadContentBatch("");
        var copied = jobs.step(id, 1);
        assertThat(copied.phase()).isEqualTo("VERIFYING");
        assertThat(copied.cursor()).isEmpty();
        jobs.step(id, 2);
        var completed = jobs.step(id, 3);
        assertThat(completed.phase()).isEqualTo("COMPLETED");
        assertThat(completed.copied()).isEqualTo(2);
        assertThat(completed.verified()).isEqualTo(2);
        assertThat(completed.copyHash()).isEqualTo(completed.verifyHash());
        assertThat(jobs.step(id, 4)).isEqualTo(completed);
    }

    @Test void missingMaintenanceAndConcurrentJobAndFutureRevisionAreRejected() {
        UUID id = UUID.randomUUID();
        assertCode(() -> jobs.start(id, false), ErrorCode.VALIDATION_FAILED);
        jobs.start(id, true);
        assertCode(() -> jobs.start(UUID.randomUUID(), true), ErrorCode.VERSION_CONFLICT);
        assertCode(() -> jobs.legacyReindex(), ErrorCode.VERSION_CONFLICT);
        assertCode(() -> jobs.step(id, 1), ErrorCode.VERSION_CONFLICT);
        assertCode(() -> jobs.step(id, -1), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> jobs.get(UUID.randomUUID()), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(jobs.cancel(id).phase()).isEqualTo("CANCELLED");
        assertThat(jobs.cancel(id).phase()).isEqualTo("CANCELLED");
        assertThat(jobs.start(UUID.randomUUID(), true).phase()).isEqualTo("COPYING");
    }

    @Test void failedWriteRollsBackCheckpointAndPartialTargetIsSafelyRetried() {
        onePage(List.of(post(2, "two"), post(1, "one")));
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            PostSearchDocument doc = call.getArgument(0);
            documents.put(doc.getId(), doc);
            if (doc.getId() == 1 && fail.getAndSet(false)) throw new IllegalStateException("after target commit");
            return null;
        }).when(projection).upsert(any());
        UUID id = UUID.randomUUID();
        var initial = jobs.start(id, true);
        assertCode(() -> jobs.step(id, 0), ErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(documents).hasSize(2);
        jobs = newJobs();
        assertThat(jobs.get(id)).isEqualTo(initial);
        assertThat(jobs.step(id, 0).phase()).isEqualTo("VERIFYING");
        assertThat(jobs.step(id, 1).phase()).isEqualTo("COMPLETED");
    }

    @Test void wrongSameVersionFieldsFailInsteadOfAcceptingAnEqualCount() {
        onePage(List.of(post(1, "one")));
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jobs.step(id, 0);
        documents.put(1L, source.document(post(1, "corrupted"), Instant.EPOCH));
        var failed = jobs.step(id, 1);
        assertThat(failed.phase()).isEqualTo("FAILED");
        assertThat(failed.lastError()).contains("PROJECTION_MISMATCH id=1", "title", "expected=", "actual=");
        assertThat(jobs.step(id, failed.revision())).isEqualTo(failed);
    }

    @Test void sourceMutationEvenWhenTargetMatchesFailsTheCrossPassFingerprint() {
        onePage(List.of(post(1, "old")));
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jobs.step(id, 0);
        onePage(List.of(post(1, "new")));
        documents.put(1L, source.document(post(1, "new"), Instant.EPOCH));
        assertThat(jobs.step(id, 1).lastError()).isEqualTo("SOURCE_CHANGED_DURING_REINDEX");
    }

    @Test void extraPublishedOrInvalidDocumentsPreventCompletion() {
        onePage(List.of(post(1, "one")));
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jobs.step(id, 0);
        when(projection.countsMatch(1)).thenReturn(false);
        assertThat(jobs.step(id, 1).lastError()).isEqualTo("INDEX_EXTRA_OR_INVALID_DOCUMENTS");
    }

    @Test void missingTargetDocumentIsADifferenceNotASuccessfulRebuild() {
        onePage(List.of(post(1, "one")));
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jobs.step(id, 0);
        documents.clear();
        assertThat(jobs.step(id, 1).lastError()).contains("PROJECTION_MISMATCH", "id", "aggregateVersion");
    }

    @Test void emptySourceRequiresEmptyPublishedIndexAndDoesNotUseSeedFallback() {
        onePage(List.of());
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jobs.step(id, 0);
        assertThat(jobs.step(id, 1).phase()).isEqualTo("COMPLETED");
        verify(projection, never()).upsert(any());
    }

    @Test void lostGuardPreventsAnOldTaskFromWriting() {
        onePage(List.of(post(1, "one")));
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jdbc.sql("UPDATE search_reindex_guard SET active_job_id=NULL").update();
        assertCode(() -> jobs.step(id, 0), ErrorCode.VERSION_CONFLICT);
        verify(projection, never()).upsert(any());
    }

    @Test void sourceLosingRowsBetweenPassesCannotPassVerification() {
        onePage(List.of(post(2, "two"), post(1, "one")));
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jobs.step(id, 0);
        onePage(List.of(post(2, "two")));
        assertThat(jobs.step(id, 1).lastError()).isEqualTo("SOURCE_CHANGED_DURING_REINDEX");
    }

    @Test void invalidPersistedPhaseIsRejectedBeforeTargetAccess() {
        UUID id = UUID.randomUUID();
        jobs.start(id, true);
        jdbc.sql("UPDATE search_reindex_job SET phase='UNKNOWN'").update();
        assertCode(() -> jobs.step(id, 0), ErrorCode.VERSION_CONFLICT);
        verifyNoInteractions(projection);
    }

    private SearchReindexJobs newJobs() { return new SearchReindexJobs(jdbc, manager, source, projection, mapper); }
    private void onePage(List<PostSummary> records) {
        doReturn(new SearchIndexService.ContentBatch(records, "", false)).when(source).loadContentBatch("");
    }
    private PostSummary post(long id, String title) {
        return new PostSummary(id, "TEXT", title, "body", "author", List.of(), List.of(), 0, 0, 0,
                "PUBLISHED", Instant.EPOCH).withAggregateVersion(2L);
    }
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
