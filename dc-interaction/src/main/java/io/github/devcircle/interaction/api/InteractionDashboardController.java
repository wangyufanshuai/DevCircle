package io.github.devcircle.interaction.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
class InteractionDashboardController {
    private final InteractionRepository repository;

    InteractionDashboardController(InteractionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/favorites")
    ApiResponse<List<Long>> favorites(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(repository.favoritePostIds(userId, 30));
    }
}
