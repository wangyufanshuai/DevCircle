package io.github.devcircle.notification.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.NotificationView;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {
    private final NotificationRepository notificationRepository;

    NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    ApiResponse<List<NotificationView>> list(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(notificationRepository.list(userId));
    }

    @PostMapping("/{id}/read")
    ApiResponse<Map<String, Object>> markRead(@RequestHeader("X-User-Id") Long userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @PathVariable Long id) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
        boolean changed = notificationRepository.markRead(userId, id, idempotencyKey);
        return ApiResponse.ok(Map.of("notificationId", id, "read", true, "duplicateRequest", !changed));
    }
}
