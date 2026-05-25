package io.github.devcircle.search;

import io.github.devcircle.search.application.SearchService;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:65535"
})
class SearchIndexIntegrationTest {
    @Container
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.2")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", elasticsearch::getHttpHostAddress);
    }

    @Autowired
    SearchService searchService;

    @Autowired
    ElasticsearchOperations elasticsearchOperations;

    @Test
    void postEventCanBeIndexedAndSearched() throws Exception {
        searchService.upsertPostFromEvent("""
                {"postId":8801,"authorId":42,"title":"Kafka Outbox 集成测试","tags":["Kafka","MySQL"]}
                """);

        PostSearchDocument document = waitForDocument("8801");
        assertThat(document).isNotNull();
        assertThat(document.getTitle()).isEqualTo("Kafka Outbox 集成测试");

        var result = searchService.search("Outbox", null, "latest");
        assertThat(result.records()).extracting("id").contains(8801L);
    }

    private PostSearchDocument waitForDocument(String id) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            PostSearchDocument document = elasticsearchOperations.get(id, PostSearchDocument.class);
            if (document != null) {
                return document;
            }
            Thread.sleep(250);
        }
        return null;
    }
}
