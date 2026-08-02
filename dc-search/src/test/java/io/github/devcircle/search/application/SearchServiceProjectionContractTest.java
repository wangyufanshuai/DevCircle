package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceProjectionContractTest {
    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    private final SearchEventCoordinator coordinator = mock(SearchEventCoordinator.class);
    private final AtomicReference<String> detailResponse = new AtomicReference<>();
    private HttpServer contentServer;
    private SearchService service;

    @BeforeEach
    void setUp() throws IOException {
        contentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        contentServer.createContext("/api/posts", exchange -> {
            byte[] body = detailResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        contentServer.start();
        service = new SearchService(operations, new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + contentServer.getAddress().getPort(), coordinator);
    }

    @AfterEach
    void stopServer() {
        contentServer.stop(0);
    }

    @Test
    void sparsePublishedEventHydratesTheCanonicalDetailBeforeIndexing() throws Exception {
        detailResponse.set("""
                {"code":"OK","data":{"id":101,"type":"TEXT","theme":"COSMOS",
                  "contentKind":"COSMIC_ARTICLE","difficulty":"HARD","solvable":true,
                  "title":"引力井中的旧信号","excerpt":"来自观测阵列的延迟回声",
                  "author":{"id":17,"username":"轨道记录员"},"tags":["宇宙","观测"],
                  "likes":8,"comments":3,"views":144,"status":"PUBLISHED",
                  "createdAt":"2026-07-30T10:00:00Z"}}
                """);
        String eventId = "11111111-2222-4333-8444-555555555555";
        Instant occurredAt = Instant.parse("2026-07-30T11:00:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(101L))).thenReturn(true);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(mock(IndexOperations.class));

        service.upsertPostFromEvent("""
                {"eventId":"11111111-2222-4333-8444-555555555555","type":"post.updated","version":1,
                 "occurredAt":"2026-07-30T11:00:00Z","producer":"dc-content","aggregateId":"101",
                 "payload":{"postId":101}}
                """);

        ArgumentCaptor<PostSearchDocument> document = ArgumentCaptor.forClass(PostSearchDocument.class);
        verify(operations).save(document.capture());
        assertThat(document.getValue().getId()).isEqualTo(101L);
        assertThat(document.getValue().getTitle()).isEqualTo("引力井中的旧信号");
        assertThat(document.getValue().getAuthor()).isEqualTo("轨道记录员");
        assertThat(document.getValue().getAuthorId()).isEqualTo(17L);
        assertThat(document.getValue().getTheme()).isEqualTo("COSMOS");
        assertThat(document.getValue().getContentKind()).isEqualTo("COSMIC_ARTICLE");
        assertThat(document.getValue().getDifficulty()).isEqualTo("HARD");
        assertThat(document.getValue().getEventOccurredAt()).isEqualTo(occurredAt);
        verify(coordinator).complete(eventId, 101L, occurredAt, "UPSERT");
    }

    @Test
    void canonicalDetailThatIsNoLongerPublishedIsRemovedInsteadOfIndexed() throws Exception {
        detailResponse.set("""
                {"code":"OK","data":{"id":102,"theme":"DEDUCTION","contentKind":"CASE_FILE",
                  "difficulty":"MEDIUM","title":"已撤回的案件","author":"记录员","tags":[],
                  "status":"CHANGES_REQUIRED","createdAt":"2026-07-30T10:00:00Z"}}
                """);
        String eventId = "22222222-3333-4444-8555-666666666666";
        Instant occurredAt = Instant.parse("2026-07-30T12:00:00Z");
        when(coordinator.claim(any(ValidatedEvent.class), eq(102L))).thenReturn(true);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);

        service.upsertPostFromEvent("""
                {"eventId":"22222222-3333-4444-8555-666666666666","type":"post.updated","version":1,
                 "occurredAt":"2026-07-30T12:00:00Z","producer":"dc-content","aggregateId":"102",
                 "payload":{"postId":102}}
                """);

        verify(operations).delete("102", PostSearchDocument.class);
        verify(indexOperations).refresh();
        verify(operations, never()).save(any(PostSearchDocument.class));
        verify(coordinator).complete(eventId, 102L, occurredAt, "REMOVE");
    }

    @Test
    void deleteFailureKeepsTheIndexErrorAndAttachesBookkeepingFailure() {
        String eventId = "33333333-4444-4555-8666-777777777777";
        Instant occurredAt = Instant.parse("2026-07-30T13:00:00Z");
        IllegalStateException indexFailure = new IllegalStateException("elasticsearch unavailable");
        IllegalStateException stateFailure = new IllegalStateException("dedup storage unavailable");
        when(coordinator.claim(any(ValidatedEvent.class), eq(103L))).thenReturn(true);
        when(operations.delete("103", PostSearchDocument.class)).thenThrow(indexFailure);
        org.mockito.Mockito.doThrow(stateFailure).when(coordinator).fail(eq(eventId), eq(103L), eq(indexFailure));

        assertThatThrownBy(() -> service.deletePostEvent("""
                {"eventId":"33333333-4444-4555-8666-777777777777","type":"post.deleted","version":1,
                 "occurredAt":"2026-07-30T13:00:00Z","producer":"dc-content","aggregateId":"103",
                 "payload":{"postId":103}}
                """))
                .isSameAs(indexFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(stateFailure));
    }
}
