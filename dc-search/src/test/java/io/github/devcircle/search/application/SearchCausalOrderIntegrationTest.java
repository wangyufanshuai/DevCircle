package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MySQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.ArrayList;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;

import static org.assertj.core.api.Assertions.*;

/** Actual MySQL transactions and Elasticsearch scripts, including the gap between their commits. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:65535", "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false", "devcircle.outbox.initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchCausalOrderIntegrationTest {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container static final ElasticsearchContainer es = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.2")
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withEnv("xpack.security.enabled", "false").withEnv("xpack.ml.enabled", "false")
            .withEnv("discovery.type", "single-node");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.elasticsearch.uris", es::getHttpHostAddress);
    }
    @TestConfiguration static class Beans {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @Autowired SearchService service;
    @Autowired SearchProjectionStore store;
    @Autowired SearchEventCoordinator coordinator;
    @Autowired ElasticsearchOperations operations;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    private static final Instant TIME = Instant.parse("2026-10-14T10:00:00Z");

    @Test void sameMillisecondAndReverseUuidOrderUseTheBusinessVersion() throws Exception {
        service.upsertPostFromEvent(event(92001, 1, "ffffffff-ffff-4fff-8fff-ffffffffffff", TIME, false, "first"));
        service.upsertPostFromEvent(event(92001, 2, "11111111-1111-4111-8111-111111111111", TIME, false, "second"));
        assertThat(document(92001).getTitle()).isEqualTo("second");
        assertThat(document(92001).getAggregateVersion()).isEqualTo(2L);
        assertThat(watermark(92001)).isEqualTo(2L);
    }

    @Test void clockRollbackDeleteSurvivesDelayedUpdatesAndDuplicateReplays() throws Exception {
        String original = event(92002, 10, UUID.randomUUID().toString(), TIME, false, "published");
        service.upsertPostFromEvent(original);
        String delete = event(92002, 11, UUID.randomUUID().toString(), TIME.minusSeconds(3600), true, "");
        service.deletePostEvent(delete);
        service.upsertPostFromEvent(original);
        service.upsertPostFromEvent(event(92002, 10, UUID.randomUUID().toString(), TIME.plusSeconds(86400), false, "stale"));
        service.deletePostEvent(delete);
        // Even another event ID cannot reinterpret an already-applied version.
        service.upsertPostFromEvent(event(92002, 11, UUID.randomUUID().toString(), TIME.plusSeconds(99999), false, "conflict"));
        assertThat(document(92002).getStatus()).isEqualTo("DELETED");
        assertThat(document(92002).getTitle()).isNull();
        assertThat(document(92002).getAggregateVersion()).isEqualTo(11L);
        assertThat(watermark(92002)).isEqualTo(11L);
        assertThat(service.search("stale", null, "latest").records()).extracting("id").doesNotContain(92002L);
    }

    @Test void indexAheadOfDatabaseRejectsStaleWritesAndReindexSnapshots() throws Exception {
        // A worker wrote ES, then died before recording its MySQL watermark.
        store.remove(92003, TIME, UUID.randomUUID().toString(), 20);
        service.upsertPostFromEvent(event(92003, 19, UUID.randomUUID().toString(), TIME.plusSeconds(100), false, "replay"));
        store.save(List.of(snapshot(92003, 19, "old reindex")));
        store.save(List.of(snapshot(92003, 20, "equal version reindex")));
        assertThat(document(92003).getStatus()).isEqualTo("DELETED");
        assertThat(document(92003).getAggregateVersion()).isEqualTo(20L);
        service.upsertPostFromEvent(event(92004, 30, UUID.randomUUID().toString(), TIME, false, "current"));
        service.deletePostEvent(event(92004, 29, UUID.randomUUID().toString(), TIME.plusSeconds(100), true, ""));
        assertThat(document(92004).getTitle()).isEqualTo("current");
        assertThat(document(92004).getAggregateVersion()).isEqualTo(30L);
    }

    @Test void expiredWorkerCannotUndoNewerDeleteEvenBeforeLeaseFailureIsRecorded() throws Exception {
        ValidatedEvent old = validated(92005, 1);
        var oldClaim = coordinator.claim(old, 92005);
        jdbc.sql("UPDATE search_event_watermark SET lease_until=TIMESTAMPADD(MINUTE,-10,CURRENT_TIMESTAMP) WHERE post_id=92005").update();
        service.deletePostEvent(event(92005, 2, UUID.randomUUID().toString(), TIME.minusSeconds(60), true, ""));
        store.upsert(snapshot(92005, 1, "late worker"));
        assertThatThrownBy(() -> coordinator.complete(old.eventId().toString(), 92005, TIME, "UPSERT", oldClaim.leaseToken()))
                .isInstanceOf(SearchEventCoordinator.SearchEventLeaseLostException.class);
        assertThat(document(92005).getStatus()).isEqualTo("DELETED");
        assertThat(watermark(92005)).isEqualTo(2L);
    }

    @Test void missingNegativeFractionalAndOverflowVersionsFailBeforeClaim() throws Exception {
        for (String version : List.of("null", "-1", "1.5", "9223372036854775808", "\"3\"")) {
            var tree = mapper.readTree(event(92006, 1, UUID.randomUUID().toString(), TIME, false, "invalid"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("payload")).set("aggregateVersion", mapper.readTree(version));
            assertThatThrownBy(() -> service.upsertPostFromEvent(mapper.writeValueAsString(tree)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aggregateVersion");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM search_consumer_dedup WHERE post_id=92006").query(Long.class).single()).isZero();
        assertThat(document(92006)).isNull();
    }

    @Test void cursorReindexPersistsMoreThanTwoThousandDocumentsAndKeepsNewerTombstone() throws Exception {
        // The HTTP source is a fixture; projection writes, scripts and reads use real Elasticsearch.
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/api/posts", exchange -> {
            String cursor = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .filter(p -> p.startsWith("cursor=")).findFirst().orElseThrow().substring(7);
            cursor = URLDecoder.decode(cursor, StandardCharsets.UTF_8);
            int highest = cursor.isEmpty() ? 102001 : Integer.parseInt(new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|")[1]) - 1;
            int lowest = Math.max(100001, highest - 19);
            List<PostSummary> records = new ArrayList<>();
            for (int id = highest; id >= lowest; id--) {
                records.add(new PostSummary((long) id, "TEXT", "post-" + id, "body-" + id,
                        "test", List.of(), List.of(), 0, 0, 0, "PUBLISHED", TIME).withAggregateVersion(2L));
            }
            boolean more = lowest > 100001;
            String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (TIME + "|" + lowest).getBytes(StandardCharsets.UTF_8)) : "";
            byte[] bytes = mapper.writeValueAsBytes(ApiResponse.ok(records, Map.of("hasMore", more, "nextCursor", next)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        source.start();
        try {
            store.remove(100001, TIME, UUID.randomUUID().toString(), 3);
            SearchIndexService reindex = new SearchIndexService(operations, mapper,
                    "http://127.0.0.1:" + source.getAddress().getPort(), coordinator);
            assertThat(reindex.reindexSeedPosts()).isEqualTo(2001);
            CriteriaQuery query = new CriteriaQuery(new Criteria("id").greaterThanEqual(100001).lessThanEqual(102001));
            query.setPageable(PageRequest.of(0, 2100));
            var results = operations.search(query, PostSearchDocument.class).getSearchHits().stream()
                    .map(hit -> hit.getContent()).toList();
            assertThat(results).hasSize(2001);
            assertThat(results.stream().map(PostSearchDocument::getId).toList()).doesNotHaveDuplicates();
            assertThat(results).allSatisfy(post -> {
                if (post.getId() == 100001) {
                    assertThat(post.getStatus()).isEqualTo("DELETED");
                    assertThat(post.getAggregateVersion()).isEqualTo(3L);
                } else {
                    assertThat(post.getStatus()).isEqualTo("PUBLISHED");
                    assertThat(post.getTitle()).isEqualTo("post-" + post.getId());
                    assertThat(post.getExcerpt()).isEqualTo("body-" + post.getId());
                    assertThat(post.getAggregateVersion()).isEqualTo(2L);
                }
            });
        } finally {
            source.stop(0);
        }
    }

    private String event(long id, long version, String eventId, Instant at, boolean delete, String title) throws Exception {
        return mapper.writeValueAsString(Map.of("eventId", eventId, "type", delete ? "post.deleted" : "post.updated",
                "version", 1, "occurredAt", at.toString(), "producer", "dc-content", "aggregateId", Long.toString(id),
                "payload", Map.of("postId", id, "aggregateVersion", version, "status", delete ? "DELETED" : "PUBLISHED",
                        "title", title, "tags", List.of())));
    }
    private ValidatedEvent validated(long id, long version) {
        return new ValidatedEvent(UUID.randomUUID(), "post.updated", 1, TIME, "dc-content", Long.toString(id),
                mapper.createObjectNode().put("postId", id).put("aggregateVersion", version));
    }
    private PostSearchDocument snapshot(long id, long version, String title) {
        return new PostSearchDocument(id, title, title, "author", List.of(), "PUBLISHED", TIME)
                .withAggregateVersion(version);
    }
    private PostSearchDocument document(long id) { return operations.get(Long.toString(id), PostSearchDocument.class); }
    private Long watermark(long id) {
        return jdbc.sql("SELECT aggregate_version FROM search_event_watermark WHERE post_id=:id").param("id", id).query(Long.class).single();
    }
}
