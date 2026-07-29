package io.github.devcircle.interaction.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.interaction.application.InteractionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import io.github.devcircle.interaction.infrastructure.InteractionRepository;

@RestController
@RequestMapping("/api/posts")
class InteractionController {
    private final InteractionService interactionService;
    private final InteractionRepository interactionRepository;

    InteractionController(InteractionService interactionService, InteractionRepository interactionRepository) {
        this.interactionService = interactionService;
        this.interactionRepository = interactionRepository;
    }

    @GetMapping("/{id}/interaction-state")
    ApiResponse<InteractionRepository.InteractionState> state(@PathVariable Long id,
                                                               @RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(interactionRepository.interactionState(userId, id));
    }

    @PostMapping("/{id}/like")
    ApiResponse<InteractionResult> like(@PathVariable Long id,
                                        @RequestHeader("X-User-Id") Long userId,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.ok(interactionService.like(userId, id, idempotencyKey));
    }

    @PostMapping("/{id}/favorite")
    ApiResponse<InteractionResult> favorite(@PathVariable Long id,
                                            @RequestHeader("X-User-Id") Long userId,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.ok(interactionService.favorite(userId, id, idempotencyKey));
    }

    @DeleteMapping("/{id}/like")
    ApiResponse<InteractionResult> unlike(@PathVariable Long id,
                                          @RequestHeader("X-User-Id") Long userId,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.ok(interactionService.unlike(userId, id, idempotencyKey));
    }

    @DeleteMapping("/{id}/favorite")
    ApiResponse<InteractionResult> unfavorite(@PathVariable Long id,
                                              @RequestHeader("X-User-Id") Long userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.ok(interactionService.unfavorite(userId, id, idempotencyKey));
    }
}
