package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServiceEventTest {
    private static final String LEASE_TOKEN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    private final SearchEventCoordinator coordinator = mock(SearchEventCoordinator.class);
    private SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(operations, new ObjectMapper().findAndRegisterModules(),
                "http://localhost:8103", coordinator);
    }

    @Test
    void rejectedContentIsRemovedWithoutCallingThePublicDetailEndpoint() throws Exception {
        String eventId = "44444444-4444-4444-4444-444444444444";
        Instant occurredAt = Instant.parse("2026-07-30T12:00:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(91L))).thenReturn(acquired());
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);

        service.upsertPostFromEvent("""
                {"eventId":"44444444-4444-4444-4444-444444444444","type":"content.rejected","version":1,
                 "occurredAt":"2026-07-30T12:00:00Z","producer":"dc-admin","aggregateId":"91",
                 "payload":{"aggregateVersion":1,"postId":91,"status":"REJECTED"}}
                """);

        verify(operations).update(any(UpdateQuery.class), any(IndexCoordinates.class));
        verify(indexOperations).refresh();
        verify(coordinator).complete(eventId, 91L, occurredAt, "REMOVE", LEASE_TOKEN);
    }

    @Test
    void publishedAuditEventIndexesItsCanonicalPayloadAndRefreshesBeforeCompletion() throws Exception {
        String eventId = "41414141-4141-4414-8414-414141414141";
        Instant occurredAt = Instant.parse("2026-07-30T12:05:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(912L))).thenReturn(acquired());
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);

        service.indexPostEvent("""
                {"eventId":"41414141-4141-4414-8414-414141414141","type":"audit.completed","version":1,
                 "occurredAt":"2026-07-30T12:05:00Z","producer":"dc-admin","aggregateId":"912",
                 "payload":{"aggregateVersion":1,"postId":912,"status":"PUBLISHED","type":"TEXT","theme":"COSMOS",
                   "contentKind":"COSMIC_ARTICLE","difficulty":"HARD","solvable":true,
                   "title":"引力井回声","excerpt":"观测阵列收到延迟信号","author":"轨道记录员",
                   "authorId":27,"tags":["宇宙","观测"]}}
                """);

        ArgumentCaptor<UpdateQuery> query = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations).update(query.capture(), any(IndexCoordinates.class));
        assertThat(query.getValue().getId()).isEqualTo("912");
        assertThat(query.getValue().getScript()).contains("aggregateVersion", "noop");
        assertThat(query.getValue().getParams().get("aggregateVersion")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        var document = (java.util.Map<String, Object>) query.getValue().getParams().get("document");
        assertThat(document).containsEntry("title", "引力井回声")
                .containsEntry("authorId", 27L)
                .containsEntry("theme", "COSMOS")
                .containsEntry("contentKind", "COSMIC_ARTICLE")
                .containsEntry("difficulty", "HARD")
                .containsEntry("eventOccurredAt", occurredAt.toEpochMilli());
        verify(indexOperations).refresh();
        verify(coordinator).complete(eventId, 912L, occurredAt, "UPSERT", LEASE_TOKEN);
    }

    @Test
    void nonPublishedUpdateRemovesTheDocumentWithoutHydratingPublicDetail() throws Exception {
        String eventId = "42424242-4242-4424-8424-424242424242";
        Instant occurredAt = Instant.parse("2026-07-30T12:10:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(913L))).thenReturn(acquired());
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);

        service.upsertPostFromEvent("""
                {"eventId":"42424242-4242-4424-8424-424242424242","type":"post.updated","version":1,
                 "occurredAt":"2026-07-30T12:10:00Z","producer":"dc-content","aggregateId":"913",
                 "payload":{"aggregateVersion":1,"postId":913,"status":"CHANGES_REQUIRED"}}
                """);

        verify(operations).update(any(UpdateQuery.class), any(IndexCoordinates.class));
        verify(indexOperations).refresh();
        verify(operations, never()).save(any(PostSearchDocument.class));
        verify(coordinator).complete(eventId, 913L, occurredAt, "REMOVE", LEASE_TOKEN);
    }

    @Test
    void duplicateUpsertEventDoesNotTouchElasticsearchOrHydrateContent() throws Exception {
        when(coordinator.claim(any(ValidatedEvent.class), eq(914L)))
                .thenReturn(SearchEventCoordinator.ClaimResult.duplicate());

        service.upsertPostFromEvent("""
                {"eventId":"43434343-4343-4434-8434-434343434343","type":"post.updated","version":1,
                 "occurredAt":"2026-07-30T12:12:00Z","producer":"dc-content","aggregateId":"914",
                 "payload":{"aggregateVersion":1,"postId":914}}
                """);

        verifyNoInteractions(operations);
        verify(coordinator, never()).complete(any(), eq(914L), any(), any(), any());
        verify(coordinator, never()).fail(any(), eq(914L), any(), any());
    }

    @Test
    void removalRefreshFailureIsRecordedAndRetriedByKafka() {
        String eventId = "4e444444-4444-4444-4444-444444444444";
        Instant occurredAt = Instant.parse("2026-07-30T12:15:00Z");
        IllegalStateException refreshFailure = new IllegalStateException("elasticsearch refresh unavailable");
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(coordinator.claim(any(ValidatedEvent.class), eq(911L))).thenReturn(acquired());
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);
        org.mockito.Mockito.doThrow(refreshFailure).when(indexOperations).refresh();

        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"4e444444-4444-4444-4444-444444444444","type":"content.rejected","version":1,
                 "occurredAt":"2026-07-30T12:15:00Z","producer":"dc-admin","aggregateId":"911",
                 "payload":{"aggregateVersion":1,"postId":911,"status":"REJECTED"}}
                """))
                .isSameAs(refreshFailure);

        verify(coordinator).fail(eq(eventId), eq(911L), eq(refreshFailure), eq(LEASE_TOKEN));
        verify(coordinator, never()).complete(any(), eq(911L), any(), any(), any());
    }

    @Test
    void duplicateOrStaleEventDoesNotTouchElasticsearch() throws Exception {
        String eventId = "55555555-5555-5555-5555-555555555555";
        Instant occurredAt = Instant.parse("2026-07-30T12:30:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(92L)))
                .thenReturn(SearchEventCoordinator.ClaimResult.duplicate());

        service.deletePostEvent("""
                {"eventId":"55555555-5555-5555-5555-555555555555","type":"post.deleted","version":1,
                 "occurredAt":"2026-07-30T12:30:00Z","producer":"dc-content","aggregateId":"92",
                 "payload":{"aggregateVersion":1,"postId":92}}
                """);

        verifyNoInteractions(operations);
        verify(coordinator, never()).complete(any(), eq(92L), any(), any(), any());
    }

    @Test
    void indexingFailureIsRecordedAndRetriedByKafka() {
        String eventId = "66666666-6666-6666-6666-666666666666";
        Instant occurredAt = Instant.parse("2026-07-30T13:00:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(93L))).thenReturn(acquired());
        org.mockito.Mockito.doThrow(new IllegalStateException("elasticsearch unavailable"))
                .when(operations).update(any(UpdateQuery.class), any(IndexCoordinates.class));

        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"66666666-6666-6666-6666-666666666666","type":"post.created","version":1,
                 "occurredAt":"2026-07-30T13:00:00Z","producer":"dc-content","aggregateId":"93",
                 "payload":{"aggregateVersion":1,"postId":93,"title":"深空回声","tags":["宇宙"],"status":"PUBLISHED"}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elasticsearch unavailable");

        verify(coordinator).fail(eq(eventId), eq(93L), any(IllegalStateException.class), eq(LEASE_TOKEN));
    }

    @Test
    void indexingFailureRemainsPrimaryWhenSearchStatePersistenceAlsoFails() {
        String eventId = "67676767-6767-4676-8676-676767676767";
        IllegalStateException indexFailure = new IllegalStateException("elasticsearch write failed");
        IllegalStateException stateFailure = new IllegalStateException("search state database failed");
        when(coordinator.claim(any(ValidatedEvent.class), eq(931L))).thenReturn(acquired());
        org.mockito.Mockito.doThrow(indexFailure)
                .when(operations).update(any(UpdateQuery.class), any(IndexCoordinates.class));
        org.mockito.Mockito.doThrow(stateFailure).when(coordinator)
                .fail(eventId, 931L, indexFailure, LEASE_TOKEN);

        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"67676767-6767-4676-8676-676767676767","type":"post.created","version":1,
                 "occurredAt":"2026-07-30T13:05:00Z","producer":"dc-content","aggregateId":"931",
                 "payload":{"aggregateVersion":1,"postId":931,"title":"失联观测站","tags":["宇宙"],"status":"PUBLISHED"}}
                """))
                .isSameAs(indexFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(stateFailure));

        verify(coordinator, never()).complete(any(), eq(931L), any(), any(), any());
    }

    @Test
    void producerAndAggregateIdentityAreRejectedBeforeClaimingSearchState() {
        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"77777777-7777-4777-8777-777777777777","type":"content.rejected","version":1,
                 "occurredAt":"2026-07-30T13:30:00Z","producer":"dc-content","aggregateId":"94",
                 "payload":{"aggregateVersion":1,"postId":94,"status":"REJECTED"}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported event producer dc-content for type content.rejected");
        assertThatThrownBy(() -> service.deletePostEvent("""
                {"eventId":"88888888-8888-4888-8888-888888888888","type":"post.deleted","version":1,
                 "occurredAt":"2026-07-30T13:30:00Z","producer":"dc-content","aggregateId":"95",
                 "payload":{"aggregateVersion":1,"postId":96}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search aggregateId does not match postId");

        verifyNoInteractions(coordinator, operations);
    }

    @Test
    void nonTextualStatusIsRecordedAsAFailedIndexingAttempt() {
        String eventId = "9e45e6c2-3020-43bd-818d-f61b629d6ce1";
        Instant occurredAt = Instant.parse("2026-07-30T14:00:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(97L))).thenReturn(acquired());

        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"9e45e6c2-3020-43bd-818d-f61b629d6ce1","type":"post.updated","version":1,
                 "occurredAt":"2026-07-30T14:00:00Z","producer":"dc-content","aggregateId":"97",
                 "payload":{"aggregateVersion":1,"postId":97,"status":7}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search payload status must be a string");

        verify(coordinator).fail(eq(eventId), eq(97L), any(IllegalArgumentException.class), eq(LEASE_TOKEN));
        verifyNoInteractions(operations);
    }

    private SearchEventCoordinator.ClaimResult acquired() {
        return SearchEventCoordinator.ClaimResult.acquired(LEASE_TOKEN);
    }
}
