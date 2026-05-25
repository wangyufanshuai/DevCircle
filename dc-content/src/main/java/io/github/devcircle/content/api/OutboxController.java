package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.event.OutboxPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/content/admin/outbox")
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

