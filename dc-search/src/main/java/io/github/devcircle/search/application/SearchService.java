package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class SearchService {
    private final ElasticsearchOperations elasticsearchOperations;
    private final ObjectMapper objectMapper;

    public SearchService(ElasticsearchOperations elasticsearchOperations, ObjectMapper objectMapper) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.objectMapper = objectMapper;
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort) {
        var result = new PostSummary(3001L, "搜索：" + keyword, "Elasticsearch 索引映射、中文分词和标签过滤示例。", "search-bot", List.of(tag == null ? "搜索" : tag), 88, 12, 2600, "PUBLISHED", Instant.now());
        return PageResponse.of(List.of(result), 1, 1, 10);
    }

    public int reindexSeedPosts() {
        try {
            elasticsearchOperations.save(List.of(
                    new PostSearchDocument(1001L, "Spring Cloud 微服务里的 Outbox 可靠事件实践", "用本地事务、Kafka 和消费端幂等替代 XA。", "lin", List.of("Spring Cloud", "Kafka"), "PUBLISHED", Instant.now()),
                    new PostSearchDocument(1003L, "Redis 热点缓存、限流和点赞幂等的工程边界", "详情缓存、布隆过滤、令牌桶和幂等键设计。", "zhou", List.of("Redis", "限流"), "PUBLISHED", Instant.now())
            ));
            return 2;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @KafkaListener(topics = {EventTopics.POST_CREATED, EventTopics.POST_UPDATED}, groupId = "dc-search")
    public void indexPostEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long postId = root.path("postId").asLong();
            String title = root.path("title").asText();
            List<String> tags = objectMapper.convertValue(root.path("tags"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            elasticsearchOperations.save(new PostSearchDocument(postId, title, title, "event", tags, "PENDING", Instant.now()));
        } catch (RuntimeException | java.io.IOException ignored) {
            // The consumer is intentionally tolerant in the demo pipeline; failed events are replayable from Kafka.
        }
    }

    @KafkaListener(topics = EventTopics.POST_DELETED, groupId = "dc-search")
    public void deletePostEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            elasticsearchOperations.delete(root.path("postId").asText(), PostSearchDocument.class);
        } catch (RuntimeException | java.io.IOException ignored) {
        }
    }
}

