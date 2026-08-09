package io.github.devcircle.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.infrastructure.JdbcOutboxPublisher;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.PostQueryRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "devcircle.outbox.initial-delay-ms=3600000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        registry.add("devcircle.outbox.poll-interval-ms", () -> "60000");
    }

    @Autowired
    PostRepository postRepository;

    @Autowired
    PostQueryRepository postQueryRepository;

    @Autowired
    JdbcOutboxPublisher outboxPublisher;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
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
        assertThat(postQueryRepository.detail(created.id()).tags()).containsExactlyInAnyOrder("Kafka", "MySQL");
        assertThat(count("SELECT COUNT(*) FROM post_content WHERE post_id = " + created.id())).isEqualTo(1);
        assertThat(outboxStatus(created.id())).isEqualTo("NEW");

        int published = outboxPublisher.publishNewEvents(10);

        assertThat(published).isEqualTo(1);
        assertThat(outboxStatus(created.id())).isEqualTo("PUBLISHED");
        assertThat(readSinglePostCreatedEvent().path("payload").path("postId").asLong()).isEqualTo(created.id());
    }

    @Test
    void repeatedInteractionEventUpdatesContentCounterExactlyOnce() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO post(id, author_id, title, status, like_count)
                        VALUES (91001, 7, 'Kafka projection case', 'PUBLISHED', 17)
                        """).update();
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "91001",
                Map.of("actorId", 42L, "postId", 91001L, "interactionType", "LIKE"));
        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(EventTopics.INTERACTION_CHANGED, "91001", payload).get();
        kafkaTemplate.send(EventTopics.INTERACTION_CHANGED, "91001", payload).get();

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && postLikeCount(91001L) != 18) {
            Thread.sleep(100);
        }

        assertThat(postLikeCount(91001L)).isEqualTo(18);
        assertThat(count("SELECT COUNT(*) FROM interaction_projection_dedup WHERE event_id = '"
                + event.eventId() + "'")).isEqualTo(1);
    }

    @Test
    void commentLifecycleEventsUpdateContentCounterExactlyOnce() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO post(id, author_id, title, status, comment_count)
                        VALUES (91002, 7, 'Comment projection case', 'PUBLISHED', 0)
                        """).update();
        EventEnvelope created = EventEnvelope.now(EventTopics.COMMENT_CREATED, "dc-comment", "91",
                Map.of("commentId", 91L, "postId", 91002L, "actorId", 42L,
                        "parentId", 0L, "changeType", "CREATED"));
        String createdPayload = objectMapper.writeValueAsString(created);
        kafkaTemplate.send(EventTopics.COMMENT_CREATED, "91002", createdPayload).get();
        kafkaTemplate.send(EventTopics.COMMENT_CREATED, "91002", createdPayload).get();
        awaitCommentCount(91002L, 1);

        EventEnvelope deleted = EventEnvelope.now(EventTopics.COMMENT_CHANGED, "dc-comment", "91",
                Map.of("commentId", 91L, "postId", 91002L, "actorId", 42L,
                        "parentId", 0L, "changeType", "DELETED"));
        kafkaTemplate.send(EventTopics.COMMENT_CHANGED, "91002", objectMapper.writeValueAsString(deleted)).get();
        awaitCommentCount(91002L, 0);

        assertThat(count("SELECT COUNT(*) FROM comment_projection_dedup WHERE event_id IN ('"
                + created.eventId() + "','" + deleted.eventId() + "')")).isEqualTo(2);
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

    private long postLikeCount(Long postId) {
        Long value = jdbcClient.sql("SELECT like_count FROM post WHERE id = :postId")
                .param("postId", postId).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private void awaitCommentCount(Long postId, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && commentCount(postId) != expected) {
            Thread.sleep(100);
        }
        assertThat(commentCount(postId)).isEqualTo(expected);
    }

    private long commentCount(Long postId) {
        Long value = jdbcClient.sql("SELECT comment_count FROM post WHERE id = :postId")
                .param("postId", postId).query(Long.class).single();
        return value == null ? 0 : value;
    }
}
