package io.github.devcircle.search;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

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
                 "payload":{"postId":8801,"authorId":42,"title":"Kafka Outbox integration",
                 "tags":["Kafka","MySQL"],"status":"PUBLISHED"}}
                """);

        PostSearchDocument document = elasticsearchOperations.get("8801", PostSearchDocument.class);
        assertThat(document).isNotNull();
        assertThat(document.getTitle()).isEqualTo("Kafka Outbox integration");
        assertThat(searchService.search("Outbox", null, "latest").records()).extracting("id").contains(8801L);
    }
}
