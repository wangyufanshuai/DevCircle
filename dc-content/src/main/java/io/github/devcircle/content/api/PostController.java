package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.application.PostService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
class PostController {
    private final PostService postService;

    PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    ApiResponse<PageResponse<PostSummary>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String theme,
                                                @RequestParam(required = false) String contentKind,
                                                @RequestParam(required = false) String difficulty) {
        return ApiResponse.ok(postService.list(page, size, type, theme, contentKind, difficulty));
    }

    @GetMapping("/{id}")
    ApiResponse<PostSummary> detail(@PathVariable Long id) {
        return ApiResponse.ok(postService.detail(id));
    }

    @GetMapping("/{id}/related")
    ApiResponse<List<PostSummary>> related(@PathVariable Long id,
                                           @RequestParam(defaultValue = "6") int size) {
        return ApiResponse.ok(postService.related(id, Math.min(size, 12)));
    }

    @PostMapping
    ApiResponse<PostSummary> create(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                    @Valid @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(postService.create(userId, request));
    }

    @PostMapping("/{id}/appeals")
    ApiResponse<Map<String, Object>> appeal(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                            @PathVariable Long id,
                                            @Valid @RequestBody AppealRequest request) {
        return ApiResponse.ok(postService.createAppeal(userId, id, request.reason()));
    }
}
