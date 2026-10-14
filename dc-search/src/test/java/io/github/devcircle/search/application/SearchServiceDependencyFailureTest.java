package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.Query;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SearchServiceDependencyFailureTest {
    private HttpServer contentServer;

    @AfterEach
    void stopServer() {
        if (contentServer != null) contentServer.stop(0);
    }

    @Test
    void strictSearchReturnsASafeDependencyFailure() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IllegalStateException outage = new IllegalStateException("elasticsearch node 10.0.0.8 is offline");
        when(operations.search(any(Query.class), any(Class.class))).thenThrow(outage);
        SearchService service = service(operations, "http://127.0.0.1:1");

        assertThatThrownBy(() -> service.searchCursor("钟楼", null, "latest", null, null, null, null, 20))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("搜索服务暂时不可用");
                    assertThat(error.getCause()).isSameAs(outage);
                });
    }

    @Test
    void reindexRejectsAnInvalidContentResponseInsteadOfReportingZeroSuccess() throws IOException {
        startContentServer("{\"code\":\"OK\",\"data\":null}");
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        SearchService service = service(operations,
                "http://127.0.0.1:" + contentServer.getAddress().getPort());

        assertThatThrownBy(service::reindexSeedPosts)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("内容服务暂时不可用，无法重建搜索索引");
                });
        verifyNoInteractions(operations);
    }

    @Test
    void reindexReturnsASafeFailureWhenElasticsearchCannotPersistTheSnapshot() throws IOException {
        startContentServer("""
                {"code":"OK","data":[],"meta":{"hasMore":false,"nextCursor":""}}
                """);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IllegalStateException outage = new IllegalStateException("cluster 10.0.0.9 rejected the bulk request");
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(index);
        org.mockito.Mockito.doThrow(outage).when(index).refresh();
        SearchService service = service(operations,
                "http://127.0.0.1:" + contentServer.getAddress().getPort());

        assertThatThrownBy(service::reindexSeedPosts)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("搜索索引暂时不可用，重建未完成");
                    assertThat(error.getCause()).isSameAs(outage);
                });
    }

    @Test
    void reindexReadsAllContentPagesAndRefreshesTheCompleteSnapshot() throws IOException {
        String firstPageRecords = IntStream.iterate(21, id -> id - 1).limit(20)
                .mapToObj(this::postJson)
                .collect(Collectors.joining(","));
        startContentServer(query -> query != null && query.contains("cursor=&")
                ? page(firstPageRecords, true, Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("2026-08-02T08:00:00Z|2".getBytes(StandardCharsets.UTF_8)))
                : page(postJson(1), false, ""));
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);
        SearchService service = service(operations,
                "http://127.0.0.1:" + contentServer.getAddress().getPort());

        assertThat(service.reindexSeedPosts()).isEqualTo(21);

        verify(operations, org.mockito.Mockito.times(21)).update(any(org.springframework.data.elasticsearch.core.query.UpdateQuery.class), any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class));
        verify(indexOperations).refresh();
    }

    @Test
    void explicitDevelopmentFallbackAppliesToFullReindexAsWellAsQueries() throws IOException {
        startContentServer("""
                {"code":"OK","data":[],"meta":{"hasMore":false,"nextCursor":""}}
                """);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(indexOperations);
        SearchService service = service(operations,
                "http://127.0.0.1:" + contentServer.getAddress().getPort());
        service.enableReadFallbackForTests();

        assertThat(service.reindexSeedPosts()).isEqualTo(180);

        verify(operations, org.mockito.Mockito.times(180)).update(any(org.springframework.data.elasticsearch.core.query.UpdateQuery.class), any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class));
        verify(indexOperations).refresh();
    }

    private void startContentServer(String response) throws IOException {
        startContentServer(ignored -> response);
    }

    private void startContentServer(Function<String, String> response) throws IOException {
        contentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        contentServer.createContext("/api/posts", exchange -> {
            byte[] body = response.apply(exchange.getRequestURI().getRawQuery()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        contentServer.start();
    }

    private String page(String records, boolean hasMore, String nextCursor) {
        return """
                {"code":"OK","data":[%s],"meta":{"hasMore":%s,"nextCursor":"%s"}}
                """.formatted(records, hasMore, nextCursor);
    }

    private String postJson(int id) {
        return """
                {"aggregateVersion":2,"id":%d,"type":"TEXT","theme":"DEDUCTION","contentKind":"CASE_FILE",
                 "difficulty":"MEDIUM","solvable":false,"solvedCount":0,"title":"恢复内容 %d",
                 "excerpt":"用于索引恢复的内容","author":"恢复验证员","authorId":7,"tags":["恢复"],
                 "mediaAssets":[],"likes":0,"comments":0,"views":0,"status":"PUBLISHED",
                 "createdAt":"2026-08-02T08:00:00Z"}
                """.formatted(id, id);
    }

    private SearchService service(ElasticsearchOperations operations, String contentBaseUrl) {
        return new SearchService(operations, new ObjectMapper().findAndRegisterModules(), contentBaseUrl,
                mock(SearchEventCoordinator.class));
    }
}
