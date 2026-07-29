package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
class ReadingProgressController {
    private final PostRepository postRepository;

    ReadingProgressController(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    @GetMapping("/reading-progress")
    ApiResponse<List<ReadingProgressSummary>> progress(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(postRepository.listReadingProgress(userId, 12));
    }

    @GetMapping("/submissions")
    ApiResponse<List<SubmissionView>> submissions(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.ok(postRepository.listSubmissions(userId, 20));
    }

}
