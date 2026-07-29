package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.content.application.DeductionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/deduction")
class DeductionController {
    private final DeductionService deductionService;

    DeductionController(DeductionService deductionService) {
        this.deductionService = deductionService;
    }

    @PostMapping("/cases/{id}/answers")
    ApiResponse<DeductionAnswerResult> answer(@RequestHeader("X-User-Id") Long userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @PathVariable Long id,
                                              @Valid @RequestBody DeductionAnswerRequest request) {
        return ApiResponse.ok(deductionService.submitAnswer(userId, id, request.answer(), idempotencyKey));
    }

    @PostMapping("/cases/{id}/reveal")
    ApiResponse<DeductionAnswerResult> reveal(@RequestHeader("X-User-Id") Long userId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @PathVariable Long id) {
        return ApiResponse.ok(deductionService.reveal(userId, id, idempotencyKey));
    }

    @GetMapping("/cases/{id}/attempts/me")
    ApiResponse<DeductionAnswerResult> myAttempt(@RequestHeader("X-User-Id") Long userId,
                                                 @PathVariable Long id) {
        return ApiResponse.ok(deductionService.myAttempt(userId, id));
    }

    @GetMapping("/leaderboard")
    ApiResponse<List<LeaderboardEntry>> leaderboard() {
        return ApiResponse.ok(deductionService.leaderboard());
    }
}
