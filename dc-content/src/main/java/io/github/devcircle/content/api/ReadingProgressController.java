package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.content.infrastructure.PostQueryRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
class ReadingProgressController {
    private final PostQueryRepository postQueryRepository;
    private final ReadingProgressRepository readingProgressRepository;

    ReadingProgressController(PostQueryRepository postQueryRepository,
                              ReadingProgressRepository readingProgressRepository) {
        this.postQueryRepository = postQueryRepository;
        this.readingProgressRepository = readingProgressRepository;
    }

    @GetMapping("/reading-progress")
    ApiResponse<List<ReadingProgressSummary>> progress(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(readingProgressRepository.list(userId, 12));
    }

    @GetMapping("/submissions")
    ApiResponse<List<SubmissionView>> submissions(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(postQueryRepository.listSubmissions(userId, 20));
    }

}
