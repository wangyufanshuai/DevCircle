package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
                {"code":"OK","data":{"records":[],"total":0,"page":1,"size":20,"totalPages":0}}
                """);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IllegalStateException outage = new IllegalStateException("cluster 10.0.0.9 rejected the bulk request");
        when(operations.save(any(Iterable.class))).thenThrow(outage);
        SearchService service = service(operations,
                "http://127.0.0.1:" + contentServer.getAddress().getPort());

        assertThatThrownBy(service::reindexSeedPosts)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("搜索索引暂时不可用，重建未完成");
                    assertThat(error.getCause()).isSameAs(outage);
                });
    }

    private void startContentServer(String response) throws IOException {
        contentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        contentServer.createContext("/api/posts", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        contentServer.start();
    }

    private SearchService service(ElasticsearchOperations operations, String contentBaseUrl) {
        return new SearchService(operations, new ObjectMapper().findAndRegisterModules(), contentBaseUrl,
                mock(SearchEventCoordinator.class));
    }
}
