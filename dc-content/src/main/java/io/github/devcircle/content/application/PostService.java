package io.github.devcircle.content.application;

import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.api.CreatePostRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PostService {
    private final List<PostSummary> demoPosts = List.of(
            new PostSummary(1001L, "Spring Cloud 微服务里的 Outbox 可靠事件实践", "用本地事务、Kafka 和消费端幂等替代 XA。", "lin", List.of("Spring Cloud", "Kafka", "MySQL"), 328, 42, 9150, "PUBLISHED", Instant.now().minusSeconds(7200)),
            new PostSummary(1002L, "Elasticsearch 技术社区搜索索引怎么设计", "字段映射、标签过滤、排序和补偿重建策略。", "chen", List.of("Elasticsearch", "搜索"), 211, 27, 6020, "PENDING", Instant.now().minusSeconds(3600))
    );

    public PageResponse<PostSummary> list(int page, int size) {
        return PageResponse.of(demoPosts, demoPosts.size(), page, size);
    }

    public PostSummary detail(Long id) {
        return demoPosts.stream()
                .filter(post -> post.id().equals(id))
                .findFirst()
                .orElse(demoPosts.getFirst());
    }

    public PostSummary create(CreatePostRequest request) {
        String excerpt = request.content().substring(0, Math.min(60, request.content().length()));
        return new PostSummary(2001L, request.title(), excerpt, "demo", request.tags(), 0, 0, 0, "PENDING", Instant.now());
    }
}

