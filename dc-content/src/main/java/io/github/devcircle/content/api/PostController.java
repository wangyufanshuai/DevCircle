package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.application.PostService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
class PostController {
    private final PostService postService;

    PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    ApiResponse<PageResponse<PostSummary>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(postService.list(page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<PostSummary> detail(@PathVariable Long id) {
        return ApiResponse.ok(postService.detail(id));
    }

    @PostMapping
    ApiResponse<PostSummary> create(@Valid @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(postService.create(request));
    }
}
