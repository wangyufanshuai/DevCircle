package io.github.devcircle.admin.application;

import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.admin.api.AuditItem;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    public List<AuditItem> pendingPosts() {
        return List.of(
                new AuditItem(1002L, "Elasticsearch 技术社区搜索索引怎么设计", "PENDING", "chen"),
                new AuditItem(1003L, "Redis 热点缓存和穿透保护", "PENDING", "yang")
        );
    }

    public AdminActionResult approve(Long postId) {
        return new AdminActionResult("APPROVE_POST", Map.of("postId", postId, "status", "PUBLISHED", "event", "audit.completed"));
    }

    public AdminActionResult reject(Long postId) {
        return new AdminActionResult("REJECT_POST", Map.of("postId", postId, "status", "REJECTED", "event", "audit.completed"));
    }

    public AdminActionResult ban(Long userId) {
        return new AdminActionResult("BAN_USER", Map.of("userId", userId, "status", "BANNED"));
    }

    public AdminActionResult createTag() {
        return new AdminActionResult("CREATE_TAG", Map.of("tagId", 88, "name", "Spring Cloud"));
    }

    public Map<String, Object> overview() {
        return Map.of("users", 12890, "posts", 4320, "pendingAudits", 18, "searchQps", 42.7);
    }
}

