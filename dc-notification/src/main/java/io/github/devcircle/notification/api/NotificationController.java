package io.github.devcircle.notification.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@ConditionalOnProperty(prefix = "devcircle.notification-runtime", name = "api-enabled",
        havingValue = "true", matchIfMissing = true)
class NotificationController {
    private final NotificationRepository notificationRepository;

    NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    ApiResponse<List<NotificationView>> list(@RequestHeader("X-User-Id") Long userId,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) Long cursor,
                                             @RequestParam(defaultValue = "20") int size) {
        requireUser(userId);
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知游标无效");
        }
        String normalizedType = normalizeType(type);
        int pageSize = Math.max(1, Math.min(size, 50));
        List<NotificationView> fetched = notificationRepository.list(userId, normalizedType, cursor, pageSize + 1);
        boolean hasMore = fetched.size() > pageSize;
        List<NotificationView> items = hasMore ? List.copyOf(fetched.subList(0, pageSize)) : fetched;
        Long next = hasMore && !items.isEmpty() ? items.getLast().id() : null;
        return ApiResponse.ok(items, Map.of("nextCursor", next == null ? "" : next.toString(),
                "hasMore", hasMore));
    }

    @GetMapping("/unread-count")
    ApiResponse<Long> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        requireUser(userId);
        return ApiResponse.ok(notificationRepository.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    ApiResponse<Map<String, Object>> markRead(@RequestHeader("X-User-Id") Long userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @PathVariable Long id) {
        requireUser(userId);
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知 ID 无效");
        }
        requireIdempotencyKey(idempotencyKey);
        boolean changed = notificationRepository.markRead(userId, id, idempotencyKey);
        return ApiResponse.ok(Map.of("notificationId", id, "read", true, "duplicateRequest", !changed));
    }

    @PostMapping("/read-all")
    ApiResponse<Map<String, Object>> markAllRead(@RequestHeader("X-User-Id") Long userId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireUser(userId);
        requireIdempotencyKey(idempotencyKey);
        return ApiResponse.ok(Map.of("updated", notificationRepository.markAllRead(userId, idempotencyKey)));
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return null;
        String normalized = type.trim().toUpperCase();
        if (!List.of("AUDIT", "FOLLOW", "INTERACTION", "SYSTEM").contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "通知类型无效");
        }
        return normalized;
    }
}
