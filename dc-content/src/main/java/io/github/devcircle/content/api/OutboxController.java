package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.messaging.outbox.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content/admin/outbox")
@ConditionalOnProperty(prefix = "devcircle.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
class OutboxController {
    private final OutboxPublisher outboxPublisher;

    OutboxController(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @PostMapping("/publish")
    ApiResponse<PublishResult> publish(@RequestParam(defaultValue = "100") int batchSize) {
        return ApiResponse.ok(new PublishResult(outboxPublisher.publishNewEvents(batchSize)));
    }

    record PublishResult(int published) {
    }
}
