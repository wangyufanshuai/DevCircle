package io.github.devcircle.notification.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.model.NotificationView;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {
    private final SimpMessagingTemplate messagingTemplate;

    NotificationController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @GetMapping
    ApiResponse<List<NotificationView>> list() {
        return ApiResponse.ok(List.of(
                new NotificationView(1L, "AUDIT", "文章审核通过", "你的文章已经进入推荐流。", false, Instant.now().minusSeconds(300)),
                new NotificationView(2L, "INTERACTION", "收到新的评论", "zhou 评论了你的文章。", true, Instant.now().minusSeconds(1200))
        ));
    }

    @PostMapping("/demo-push")
    ApiResponse<Void> demoPush() {
        messagingTemplate.convertAndSend("/topic/notifications", new NotificationView(3L, "SYSTEM", "实时通知", "WebSocket 推送链路已连通。", false, Instant.now()));
        return ApiResponse.ok();
    }
}

