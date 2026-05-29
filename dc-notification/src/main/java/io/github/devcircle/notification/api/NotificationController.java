package io.github.devcircle.notification.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.model.NotificationView;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationRepository notificationRepository;

    NotificationController(SimpMessagingTemplate messagingTemplate, NotificationRepository notificationRepository) {
        this.messagingTemplate = messagingTemplate;
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    ApiResponse<List<NotificationView>> list(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId) {
        try {
            return ApiResponse.ok(notificationRepository.list(userId));
        } catch (DataAccessException ex) {
            return ApiResponse.ok(List.of(
                    new NotificationView(1L, "AUDIT", "文章审核通过", "你的作品已进入推荐流。", false, Instant.now().minusSeconds(300)),
                    new NotificationView(2L, "INTERACTION", "收到新的评论", "MysticVerse 用户评论了你的主题档案。", true, Instant.now().minusSeconds(1200))
            ));
        }
    }

    @PostMapping("/demo-push")
    ApiResponse<Void> demoPush(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId) {
        NotificationView notification = new NotificationView(3L, "SYSTEM", "实时通知", "WebSocket 推送链路已连通。", false, Instant.now());
        try {
            notificationRepository.create(userId, notification.type(), notification.title(), notification.content());
        } catch (DataAccessException ignored) {
        }
        messagingTemplate.convertAndSend("/topic/notifications", notification);
        return ApiResponse.ok();
    }
}
