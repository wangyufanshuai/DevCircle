package io.github.devcircle.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.search.application.SearchService;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:65535",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:search-index;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.locations=classpath:db/migration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchIndexIntegrationTest {
    @Container
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.2")
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.ml.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", elasticsearch::getHttpHostAddress);
    }

    @Autowired SearchService searchService;
    @Autowired ElasticsearchOperations elasticsearchOperations;

    @TestConfiguration
    static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Test
    void publishedEnvelopeCanBeIndexedAndSearched() throws Exception {
        searchService.upsertPostFromEvent("""
                {"eventId":"ab093343-47bb-4b59-902c-260ece9e80ff","type":"post.created","version":1,
                 "occurredAt":"2026-07-28T10:00:00Z","producer":"dc-content","aggregateId":"8801",
                 "payload":{"aggregateVersion":1,"postId":8801,"authorId":42,"title":"Kafka Outbox integration",
                 "tags":["Kafka","MySQL"],"status":"PUBLISHED"}}
                """);

        PostSearchDocument document = elasticsearchOperations.get("8801", PostSearchDocument.class);
        assertThat(document).isNotNull();
        assertThat(document.getTitle()).isEqualTo("Kafka Outbox integration");
        assertThat(searchService.search("Outbox", null, "latest").records()).extracting("id").contains(8801L);
    }

    @Test
    void staleEventsCannotOverwriteNewerIndexAndRejectionRemovesTheDocument() throws Exception {
        searchService.upsertPostFromEvent("""
                {"eventId":"bc093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2026-07-29T12:00:00Z","producer":"dc-content","aggregateId":"8811",
                 "payload":{"aggregateVersion":2,"postId":8811,"title":"neweralpha","excerpt":"newversionalpha","tags":["宇宙"],"status":"PUBLISHED"}}
                """);
        searchService.upsertPostFromEvent("""
                {"eventId":"cc093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2026-07-29T11:00:00Z","producer":"dc-content","aggregateId":"8811",
                 "payload":{"aggregateVersion":1,"postId":8811,"title":"olderbeta","excerpt":"oldversionbeta","tags":["宇宙"],"status":"PUBLISHED"}}
                """);

        PostSearchDocument document = elasticsearchOperations.get("8811", PostSearchDocument.class);
        assertThat(document).isNotNull();
        assertThat(document.getTitle()).isEqualTo("neweralpha");
        assertThat(searchService.search("olderbeta", null, "latest").records()).isEmpty();

        searchService.upsertPostFromEvent("""
                {"eventId":"dc093343-47bb-4b59-902c-260ece9e80ff","type":"content.rejected","version":1,
                 "occurredAt":"2026-07-29T13:00:00Z","producer":"dc-admin","aggregateId":"8811",
                 "payload":{"aggregateVersion":3,"postId":8811,"status":"REJECTED"}}
                """);

        PostSearchDocument tombstone = elasticsearchOperations.get("8811", PostSearchDocument.class);
        assertThat(tombstone).isNotNull();
        assertThat(tombstone.getStatus()).isEqualTo("DELETED");
        assertThat(tombstone.getProjectionVersion()).isEqualTo(Instant.parse("2026-07-29T13:00:00Z").toEpochMilli());
        assertThat(searchService.search("neweralpha", null, "latest").records()).isEmpty();

        searchService.upsertPostFromEvent("""
                {"eventId":"dd093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2026-07-29T12:30:00Z","producer":"dc-content","aggregateId":"8811",
                 "payload":{"aggregateVersion":2,"postId":8811,"title":"resurrectedstale","excerpt":"resurrectedstale","tags":[],"status":"PUBLISHED"}}
                """);

        assertThat(elasticsearchOperations.get("8811", PostSearchDocument.class).getStatus()).isEqualTo("DELETED");
        assertThat(searchService.search("resurrectedstale", null, "latest").records()).isEmpty();
    }

    @Test
    void staleUpsertCannotOverwriteAProjectionThatIsNewerThanTheDatabaseWatermark() throws Exception {
        Instant createdAt = Instant.parse("2026-07-29T10:00:00Z");
        Instant newerEvent = Instant.parse("2026-07-29T14:00:00Z");
        elasticsearchOperations.save(new PostSearchDocument(8841L, "authoritative", "authoritative", "event",
                7L, List.of(), "PUBLISHED", "COSMOS", "COSMIC_ARTICLE", "MEDIUM",
                createdAt, newerEvent, newerEvent.toEpochMilli(), "newer-event").withAggregateVersion(3L));
        elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();

        searchService.upsertPostFromEvent("""
                {"eventId":"ee093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2026-07-29T13:00:00Z","producer":"dc-content","aggregateId":"8841",
                 "payload":{"aggregateVersion":1,"postId":8841,"title":"stale-overwrite","excerpt":"stale-overwrite","tags":[],"status":"PUBLISHED"}}
                """);

        assertThat(elasticsearchOperations.get("8841", PostSearchDocument.class).getTitle()).isEqualTo("authoritative");
    }

    @Test
    void staleDeleteCannotRemoveAProjectionThatIsNewerThanTheDatabaseWatermark() throws Exception {
        Instant createdAt = Instant.parse("2026-07-29T10:00:00Z");
        Instant newerEvent = Instant.parse("2026-07-29T14:00:00Z");
        elasticsearchOperations.save(new PostSearchDocument(8842L, "authoritative-delete", "authoritative-delete", "event",
                7L, List.of(), "PUBLISHED", "COSMOS", "COSMIC_ARTICLE", "MEDIUM",
                createdAt, newerEvent, newerEvent.toEpochMilli(), "newer-delete-event").withAggregateVersion(3L));
        elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();

        searchService.deletePostEvent("""
                {"eventId":"fe093343-47bb-4b59-902c-260ece9e80ff","type":"post.deleted","version":1,
                 "occurredAt":"2026-07-29T13:00:00Z","producer":"dc-content","aggregateId":"8842",
                 "payload":{"aggregateVersion":1,"postId":8842}}
                """);

        assertThat(elasticsearchOperations.get("8842", PostSearchDocument.class).getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void latestCursorUsesSearchAfterAndDoesNotRepeatWhenNewContentArrives() throws Exception {
        searchService.upsertPostFromEvent("""
                {"eventId":"ec093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2099-01-01T10:00:00Z","producer":"dc-content","aggregateId":"8821",
                 "payload":{"aggregateVersion":1,"postId":8821,"title":"cursorfirst","excerpt":"cursorfirst","tags":[],"status":"PUBLISHED"}}
                """);
        searchService.upsertPostFromEvent("""
                {"eventId":"fc093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2099-01-01T09:00:00Z","producer":"dc-content","aggregateId":"8822",
                 "payload":{"aggregateVersion":1,"postId":8822,"title":"cursorsecond","excerpt":"cursorsecond","tags":[],"status":"PUBLISHED"}}
                """);

        var first = searchService.searchCursor("", null, "latest", null, null, null, null, 1);
        assertThat(first.records()).extracting("id").containsExactly(8821L);
        assertThat(first.nextCursor()).isNotBlank();

        searchService.upsertPostFromEvent("""
                {"eventId":"ac093343-47bb-4b59-902c-260ece9e80ff","type":"post.updated","version":1,
                 "occurredAt":"2099-01-01T11:00:00Z","producer":"dc-content","aggregateId":"8823",
                 "payload":{"aggregateVersion":1,"postId":8823,"title":"cursornew","excerpt":"cursornew","tags":[],"status":"PUBLISHED"}}
                """);

        var second = searchService.searchCursor("", null, "latest", null, null, null, first.nextCursor(), 1);
        assertThat(second.records()).extracting("id").containsExactly(8822L);
        assertThat(second.records()).extracting("id").doesNotContain(8821L, 8823L);
        assertThatThrownBy(() -> searchService.searchCursor("", null, "latest", "COSMOS", null, null,
                first.nextCursor(), 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void relevanceCursorRetainsScoreAndTieBreakersAcrossPages() throws Exception {
        searchService.upsertPostFromEvent("""
                {"eventId":"bc093343-47bb-4b59-902c-260ece9e80aa","type":"post.updated","version":1,
                 "occurredAt":"2098-01-01T10:00:00Z","producer":"dc-content","aggregateId":"8831",
                 "payload":{"aggregateVersion":1,"postId":8831,"title":"relevance signal","excerpt":"relevance signal","tags":[],"status":"PUBLISHED"}}
                """);
        searchService.upsertPostFromEvent("""
                {"eventId":"cc093343-47bb-4b59-902c-260ece9e80aa","type":"post.updated","version":1,
                 "occurredAt":"2098-01-01T09:00:00Z","producer":"dc-content","aggregateId":"8832",
                 "payload":{"aggregateVersion":1,"postId":8832,"title":"relevance signal","excerpt":"relevance signal","tags":[],"status":"PUBLISHED"}}
                """);

        var first = searchService.searchCursor("relevance", null, "relevance", null, null, null, null, 1);
        assertThat(first.records()).extracting("id").containsExactly(8831L);
        var second = searchService.searchCursor("relevance", null, "relevance", null, null, null,
                first.nextCursor(), 1);
        assertThat(second.records()).extracting("id").containsExactly(8832L);
    }
}
