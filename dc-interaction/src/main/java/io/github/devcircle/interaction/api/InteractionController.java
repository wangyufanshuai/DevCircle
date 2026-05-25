package io.github.devcircle.interaction.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.interaction.application.InteractionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
class InteractionController {
    private final InteractionService interactionService;

    InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @PostMapping("/{id}/like")
    ApiResponse<InteractionResult> like(@PathVariable Long id,
                                        @RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(interactionService.like(userId, id, idempotencyKey));
    }

    @PostMapping("/{id}/favorite")
    ApiResponse<InteractionResult> favorite(@PathVariable Long id,
                                            @RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(interactionService.favorite(userId, id, idempotencyKey));
    }
}
