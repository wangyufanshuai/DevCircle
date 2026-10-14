package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchReindexPagingTest {
    private static final String CREATED = "2026-08-02T08:00:00Z";
    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    private final IndexOperations index = mock(IndexOperations.class);
    private final List<Long> written = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1999, 2000, 2001, 10000})
    void readsEveryIdExactlyOnceAcrossTheOldLimitAndWritesBeforeRequestingMore(int count) throws Exception {
        AtomicInteger pageRequests = new AtomicInteger();
        AtomicInteger processedBeforeNextRequest = new AtomicInteger();
        SearchService service = service(query -> {
            String cursor = cursorParameter(query);
            int highest = cursor.isEmpty() ? count : Integer.parseInt(decode(cursor).split("\\|")[1]) - 1;
            if (written.size() == count - highest) processedBeforeNextRequest.incrementAndGet();
            pageRequests.incrementAndGet();
            int size = Math.min(20, highest);
            int lowest = highest - size + 1;
            return page(records(highest, size), lowest > 1, lowest > 1 ? cursor(lowest) : "");
        });

        assertThat(service.reindexSeedPosts()).isEqualTo(count);
        assertThat(written).containsExactlyElementsOf(IntStream.iterate(count, id -> id - 1)
                .limit(count).mapToObj(id -> (long) id).toList());
        assertThat(pageRequests.get()).isEqualTo(Math.max(1, (count + 19) / 20));
        assertThat(processedBeforeNextRequest.get()).isEqualTo(pageRequests.get());
        verify(index).refresh();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"ERROR\",\"data\":[],\"meta\":{\"hasMore\":false,\"nextCursor\":\"\"}}",
            "{\"code\":\"OK\",\"data\":[],\"meta\":{\"hasMore\":\"false\",\"nextCursor\":\"\"}}",
            "{\"code\":\"OK\",\"data\":[],\"meta\":{\"hasMore\":false}}",
            "{\"code\":\"OK\",\"data\":[],\"meta\":{\"hasMore\":true,\"nextCursor\":\"x\"}}",
            "{\"code\":\"OK\",\"data\":[],\"meta\":{\"hasMore\":false,\"nextCursor\":\"x\"}}"
    })
    void malformedOrIncompleteResponseCannotReportSuccess(String response) throws Exception {
        SearchService service = service(query -> response);
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(written).isEmpty();
        verify(index, never()).refresh();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-base64!", "other-time", "wrong-id", "missing-id"})
    void cursorMustPointToLastRecord(String defect) throws Exception {
        String next = switch (defect) {
            case "other-time" -> encode("2026-08-03T08:00:00Z|1");
            case "wrong-id" -> cursor(2);
            case "missing-id" -> encode(CREATED);
            default -> defect;
        };
        SearchService service = service(query -> page(records(20, 20), true, next));
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(written).isEmpty();
    }

    @Test
    void repeatedPageIsRejectedWithoutRewritingItOrReportingComplete() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        SearchService service = service(query -> {
            requests.incrementAndGet();
            return page(records(40, 20), true, cursor(21));
        });
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(written).hasSize(20).doesNotHaveDuplicates();
        verify(index, never()).refresh();
    }

    @Test
    void overlappingTerminalPageCannotHideDuplicateIds() throws Exception {
        SearchService service = service(query -> cursorParameter(query).isEmpty()
                ? page(records(21, 20), true, cursor(2))
                : page(post(2) + "," + post(1), false, ""));
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(written).hasSize(20).doesNotHaveDuplicates();
        verify(index, never()).refresh();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "missing"})
    void invalidVersionAnywhereInBatchRejectsTheWholeBatch(String version) throws Exception {
        String invalid = version.equals("missing") ? post(1).replace("\"aggregateVersion\":2,", "")
                : post(1).replace("\"aggregateVersion\":2", "\"aggregateVersion\":" + version);
        SearchService service = service(query -> page(post(2) + "," + invalid, false, ""));
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(written).isEmpty();
    }

    @Test
    void laterSourceFailureReportsFailureAndRetryTraversesFromStart() throws Exception {
        AtomicInteger fail = new AtomicInteger(1);
        SearchService service = service(query -> cursorParameter(query).isEmpty()
                ? page(records(21, 20), true, cursor(2))
                : fail.get() == 1 ? "{\"code\":\"ERROR\",\"data\":null}" : page(post(1), false, ""));
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(written).hasSize(20);
        verify(index, never()).refresh();
        fail.set(0);
        written.clear();
        assertThat(service.reindexSeedPosts()).isEqualTo(21);
        assertThat(written).hasSize(21).doesNotHaveDuplicates();
        verify(index).refresh();
    }

    @Test
    void targetWriteFailureDoesNotRequestAnotherBatch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        SearchService service = service(query -> {
            requests.incrementAndGet();
            return page(records(40, 20), true, cursor(21));
        });
        doThrow(new IllegalStateException("target unavailable")).when(operations)
                .update(any(UpdateQuery.class), any(IndexCoordinates.class));
        assertThatThrownBy(service::reindexSeedPosts).isInstanceOf(BusinessException.class);
        assertThat(requests.get()).isEqualTo(1);
        verify(index, never()).refresh();
    }

    private SearchService service(Function<String, String> response) throws Exception {
        when(operations.indexOps(PostSearchDocument.class)).thenReturn(index);
        when(operations.update(any(UpdateQuery.class), any(IndexCoordinates.class))).thenAnswer(invocation -> {
            UpdateQuery query = invocation.getArgument(0);
            written.add(Long.parseLong(query.getId()));
            return null;
        });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/posts", exchange -> {
            byte[] bytes = response.apply(exchange.getRequestURI().getRawQuery()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new SearchService(operations, new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + server.getAddress().getPort(), mock(SearchEventCoordinator.class));
    }

    private static String cursorParameter(String query) {
        for (String pair : query.split("&")) {
            if (pair.startsWith("cursor=")) return URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Reindex must request the cursor API");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String cursor(int id) { return encode(CREATED + "|" + id); }
    private static String records(int highest, int size) {
        return IntStream.iterate(highest, id -> id - 1).limit(size).mapToObj(SearchReindexPagingTest::post)
                .collect(Collectors.joining(","));
    }
    private static String page(String records, boolean more, String cursor) {
        return "{\"code\":\"OK\",\"data\":[" + records + "],\"meta\":{\"hasMore\":" + more + ",\"nextCursor\":\"" + cursor + "\"}}";
    }
    private static String post(int id) {
        return """
                {"aggregateVersion":2,"id":%d,"title":"post %d","excerpt":"body","status":"PUBLISHED",
                 "createdAt":"%s","tags":[],"author":"test","solvable":false,
                 "solvedCount":0,"likes":0,"comments":0,"views":0}
                """.formatted(id, id, CREATED);
    }
}
