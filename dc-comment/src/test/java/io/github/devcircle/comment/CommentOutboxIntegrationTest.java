package io.github.devcircle.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.comment.application.PostPublicationGateway;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
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
@SpringBootTest(properties = "devcircle.outbox.initial-delay-ms=3600000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(CommentOutboxIntegrationTest.TestBeans.class)
class CommentOutboxIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_comment")
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
    CommentRepository repository;

    @Autowired
    ReliableOutboxWorker outboxWorker;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbcClient.sql("DELETE FROM post_comment_counter").update();
        jdbcClient.sql("DELETE FROM comment").update();
        jdbcClient.sql("DELETE FROM outbox_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
    }

    @Test
    void commentOutboxPublishesARealEvent() throws Exception {
        var comment = repository.create(7L, 1001L, null, "Kafka-backed clue", "comment-event-key");
        assertThat(outboxWorker.publishBatch(10)).isEqualTo(1);

        JsonNode event = readEvent();
        assertThat(event.path("type").asText()).isEqualTo(EventTopics.COMMENT_CHANGED);
        assertThat(event.path("payload").path("commentId").asLong()).isEqualTo(comment.id());
        assertThat(event.path("payload").path("commentCount").asLong()).isEqualTo(1L);
        assertThat(event.path("payload").path("counterVersion").asLong()).isEqualTo(1L);
        assertThat(jdbcClient.sql("SELECT status FROM outbox_event WHERE aggregate_id = :id")
                .param("id", comment.id().toString()).query(String.class).single()).isEqualTo("PUBLISHED");
    }

    private JsonNode readEvent() throws Exception {
        try (var consumer = new KafkaConsumer<String, String>(consumerProperties())) {
            consumer.subscribe(List.of(EventTopics.COMMENT_CHANGED));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    return objectMapper.readTree(record.value());
                }
            }
        }
        throw new AssertionError("No comment.changed event was consumed from Kafka");
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dc-comment-test-" + System.nanoTime());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        PostPublicationGateway postPublicationGateway() {
            return postId -> { };
        }
    }
}
