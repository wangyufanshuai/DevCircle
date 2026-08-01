package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServiceEventTest {
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
        when(coordinator.claim(eventId, "content.rejected", 91L, occurredAt)).thenReturn(true);

        service.upsertPostFromEvent("""
                {"eventId":"44444444-4444-4444-4444-444444444444","type":"content.rejected","version":1,
                 "occurredAt":"2026-07-30T12:00:00Z","producer":"dc-admin","aggregateId":"91",
                 "payload":{"postId":91,"status":"REJECTED"}}
                """);

        verify(operations).delete("91", PostSearchDocument.class);
        verify(coordinator).complete(eventId, 91L, occurredAt, "REMOVE");
    }

    @Test
    void duplicateOrStaleEventDoesNotTouchElasticsearch() throws Exception {
        String eventId = "55555555-5555-5555-5555-555555555555";
        Instant occurredAt = Instant.parse("2026-07-30T12:30:00Z");
        when(coordinator.claim(eventId, "post.deleted", 92L, occurredAt)).thenReturn(false);

        service.deletePostEvent("""
                {"eventId":"55555555-5555-5555-5555-555555555555","type":"post.deleted","version":1,
                 "occurredAt":"2026-07-30T12:30:00Z","producer":"dc-content","aggregateId":"92",
                 "payload":{"postId":92}}
                """);

        verifyNoInteractions(operations);
        verify(coordinator, never()).complete(any(), eq(92L), any(), any());
    }

    @Test
    void indexingFailureIsRecordedAndRetriedByKafka() {
        String eventId = "66666666-6666-6666-6666-666666666666";
        Instant occurredAt = Instant.parse("2026-07-30T13:00:00Z");
        when(coordinator.claim(eventId, "post.created", 93L, occurredAt)).thenReturn(true);
        when(operations.save(any(PostSearchDocument.class))).thenThrow(new IllegalStateException("elasticsearch unavailable"));

        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"66666666-6666-6666-6666-666666666666","type":"post.created","version":1,
                 "occurredAt":"2026-07-30T13:00:00Z","producer":"dc-content","aggregateId":"93",
                 "payload":{"postId":93,"title":"深空回声","tags":["宇宙"],"status":"PUBLISHED"}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elasticsearch unavailable");

        verify(coordinator).fail(eq(eventId), eq(93L), any(IllegalStateException.class));
    }

    @Test
    void producerAndAggregateIdentityAreRejectedBeforeClaimingSearchState() {
        assertThatThrownBy(() -> service.upsertPostFromEvent("""
                {"eventId":"77777777-7777-4777-8777-777777777777","type":"content.rejected","version":1,
                 "occurredAt":"2026-07-30T13:30:00Z","producer":"dc-content","aggregateId":"94",
                 "payload":{"postId":94,"status":"REJECTED"}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported event producer dc-content for type content.rejected");
        assertThatThrownBy(() -> service.deletePostEvent("""
                {"eventId":"88888888-8888-4888-8888-888888888888","type":"post.deleted","version":1,
                 "occurredAt":"2026-07-30T13:30:00Z","producer":"dc-content","aggregateId":"95",
                 "payload":{"postId":96}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search aggregateId does not match postId");

        verifyNoInteractions(coordinator, operations);
    }
}
