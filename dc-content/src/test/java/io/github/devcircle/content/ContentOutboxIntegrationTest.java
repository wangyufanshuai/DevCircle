package io.github.devcircle.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.infrastructure.JdbcOutboxPublisher;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Sql(scripts = {
        "classpath:db/migration/V1__content_schema.sql",
        "classpath:db/migration/V2__content_details_and_audit.sql",
        "classpath:db/migration/V3__governance_and_experiment.sql"
})
class ContentOutboxIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    PostRepository postRepository;

    @Autowired
    JdbcOutboxPublisher outboxPublisher;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void creatingPostWritesContentTagsAndOutboxInOneTransactionThenPublishesKafkaEvent() throws Exception {
        var created = postRepository.create(42L, new CreatePostRequest(
                "Testcontainers 验证 Outbox",
                "发帖时必须同时写入帖子正文、标签关系和 outbox_event。",
                List.of("Kafka", "MySQL")
        ));

        assertThat(created.id()).isPositive();
        assertThat(postRepository.detail(created.id()).tags()).containsExactlyInAnyOrder("Kafka", "MySQL");
        assertThat(count("SELECT COUNT(*) FROM post_content WHERE post_id = " + created.id())).isEqualTo(1);
        assertThat(outboxStatus(created.id())).isEqualTo("NEW");

        int published = outboxPublisher.publishNewEvents(10);

        assertThat(published).isEqualTo(1);
        assertThat(outboxStatus(created.id())).isEqualTo("PUBLISHED");
        assertThat(readSinglePostCreatedEvent().path("payload").path("postId").asLong()).isEqualTo(created.id());
    }

    private JsonNode readSinglePostCreatedEvent() throws Exception {
        try (var consumer = new KafkaConsumer<String, String>(consumerProperties())) {
            consumer.subscribe(List.of(EventTopics.POST_SUBMITTED));
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    return objectMapper.readTree(record.value());
                }
            }
        }
        throw new AssertionError("No post.created event was consumed from Kafka");
    }

    private Properties consumerProperties() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dc-content-test-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }

    private String outboxStatus(Long postId) {
        return jdbcClient.sql("SELECT status FROM outbox_event WHERE aggregate_id = :postId")
                .param("postId", postId.toString())
                .query(String.class)
                .single();
    }

    private Long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
