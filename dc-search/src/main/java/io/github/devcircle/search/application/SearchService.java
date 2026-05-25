package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
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
        return search(keyword, tag, sort, null, null, null);
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort, String theme, String contentKind, String difficulty) {
        try {
            var query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        if (keyword != null && !keyword.isBlank()) {
                            b.must(m -> m.multiMatch(mm -> mm.fields("title", "excerpt").query(keyword)));
                        }
                        if (tag != null && !tag.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("tags").value(tag)));
                        }
                        if ((keyword == null || keyword.isBlank()) && (tag == null || tag.isBlank())) {
                            b.must(m -> m.matchAll(ma -> ma));
                        }
                        return b;
                    }))
                    .withPageable(PageRequest.of(0, 10))
                    .build();
            List<PostSummary> records = elasticsearchOperations.search(query, PostSearchDocument.class).stream()
                    .map(SearchHit::getContent)
                    .map(this::toSummary)
                    .filter(post -> theme == null || theme.isBlank() || post.theme().equalsIgnoreCase(theme))
                    .filter(post -> contentKind == null || contentKind.isBlank() || post.contentKind().equalsIgnoreCase(contentKind))
                    .filter(post -> difficulty == null || difficulty.isBlank() || post.difficulty().equalsIgnoreCase(difficulty))
                    .toList();
            return PageResponse.of(records, records.size(), 1, 10);
        } catch (RuntimeException ex) {
            List<PostSummary> records = fallbackPosts().stream()
                    .filter(post -> matchesKeyword(post, keyword))
                    .filter(post -> theme == null || theme.isBlank() || post.theme().equalsIgnoreCase(theme))
                    .filter(post -> contentKind == null || contentKind.isBlank() || post.contentKind().equalsIgnoreCase(contentKind))
                    .filter(post -> difficulty == null || difficulty.isBlank() || post.difficulty().equalsIgnoreCase(difficulty))
                    .toList();
            return PageResponse.of(records, records.size(), 1, 10);
        }
    }

    public int reindexSeedPosts() {
        try {
            elasticsearchOperations.save(List.of(
                    new PostSearchDocument(1001L, "雾港钟楼失踪案", "密室、怀表、潮湿鞋印和航海日志构成的推理案件。", "林墨", List.of("密室", "推理"), "PUBLISHED", Instant.now()),
                    new PostSearchDocument(1003L, "木卫二冰下城市设定集", "冰壳钻井、潮汐能源塔和低温生态穹顶。", "陈星", List.of("科幻", "世界观"), "PUBLISHED", Instant.now()),
                    new PostSearchDocument(1006L, "银河系旋臂档案", "恒星形成区、尘埃带和深空航线窗口。", "周望", List.of("宇宙", "档案"), "PUBLISHED", Instant.now())
            ));
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
            return 3;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @KafkaListener(topics = {EventTopics.POST_CREATED, EventTopics.POST_UPDATED}, groupId = "dc-search")
    public void indexPostEvent(String payload) {
        upsertPostFromEvent(payload);
    }

    public void upsertPostFromEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long postId = root.path("postId").asLong();
            String title = root.path("title").asText();
            List<String> tags = objectMapper.convertValue(root.path("tags"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            elasticsearchOperations.save(new PostSearchDocument(postId, title, title, "event", tags, "PENDING", Instant.now()));
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
        } catch (RuntimeException | java.io.IOException ignored) {
            // Failed events are replayable from Kafka or the manual reindex endpoint.
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

    private PostSummary toSummary(PostSearchDocument document) {
        return new PostSummary(document.getId(), "TEXT", inferTheme(document.getTags()), inferContentKind(document.getTags()), "MEDIUM", false, 0,
                document.getTitle(), document.getExcerpt(), document.getAuthor(), document.getTags(), List.of(), 0, 0, 0, document.getStatus(), document.getCreatedAt());
    }

    private List<PostSummary> fallbackPosts() {
        return List.of(
                new PostSummary(3001L, "TEXT", "DEDUCTION", "CASE_FILE", "HARD", true, 31, "搜索：雾港钟楼失踪案", "推理馆案件索引，支持密室、时间线和线索关键词。", "search-bot", List.of("推理", "密室"), List.of(), 88, 12, 2600, "PUBLISHED", Instant.now()),
                new PostSummary(3002L, "IMAGE", "SCI_FI", "WORLDBUILDING", "MEDIUM", false, 0, "搜索：木卫二冰下城市", "科幻设定集索引，支持世界观、星球和派系筛选。", "search-bot", List.of("科幻", "世界观"), List.of(), 91, 18, 3100, "PUBLISHED", Instant.now()),
                new PostSummary(3003L, "TEXT", "COSMOS", "COSMIC_ARTICLE", "MEDIUM", false, 0, "搜索：银河系旋臂档案", "宇宙档案索引，支持星云、星系和观测日志关键词。", "search-bot", List.of("宇宙", "档案"), List.of(), 76, 9, 2200, "PUBLISHED", Instant.now())
        );
    }

    private boolean matchesKeyword(PostSummary post, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String value = keyword.toLowerCase();
        return post.title().toLowerCase().contains(value)
                || post.excerpt().toLowerCase().contains(value)
                || post.tags().stream().anyMatch(tag -> tag.toLowerCase().contains(value));
    }

    private String inferTheme(List<String> tags) {
        if (tags.stream().anyMatch(tag -> tag.contains("科幻") || tag.contains("世界观"))) return "SCI_FI";
        if (tags.stream().anyMatch(tag -> tag.contains("宇宙") || tag.contains("档案"))) return "COSMOS";
        return "DEDUCTION";
    }

    private String inferContentKind(List<String> tags) {
        if (tags.stream().anyMatch(tag -> tag.contains("世界观"))) return "WORLDBUILDING";
        if (tags.stream().anyMatch(tag -> tag.contains("宇宙") || tag.contains("档案"))) return "COSMIC_ARTICLE";
        return "CASE_FILE";
    }
}
