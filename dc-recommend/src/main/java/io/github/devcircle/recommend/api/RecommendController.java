package io.github.devcircle.recommend.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.model.PostSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/recommend")
class RecommendController {
    @GetMapping("/feed")
    ApiResponse<List<RecommendedPost>> feed() {
        return ApiResponse.ok(List.of(
                new RecommendedPost(new PostSummary(4101L, "为什么技术社区推荐系统先做规则排序", "热度、关注关系、标签偏好比一开始训练模型更可控。", "miao", List.of("推荐系统", "架构"), 126, 19, 4300, "PUBLISHED", Instant.now()), "你关注了 Spring Cloud 和搜索标签", 0.92),
                new RecommendedPost(new PostSummary(4102L, "Kafka 事件驱动下的通知系统设计", "站内信、WebSocket 推送、离线落库和重复消费处理。", "wang", List.of("Kafka", "WebSocket"), 97, 14, 3100, "PUBLISHED", Instant.now()), "近期热度上升且评论活跃", 0.84)
        ));
    }

    record RecommendedPost(PostSummary post, String reason, double score) {
    }
}

