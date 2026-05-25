package io.github.devcircle.comment.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.comment.application.CommentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/comments")
class CommentController {
    private final CommentService commentService;

    CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    ApiResponse<List<CommentView>> list(@RequestParam Long postId) {
        return ApiResponse.ok(commentService.list(postId));
    }

    @PostMapping
    ApiResponse<CommentView> create(@Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.ok(commentService.create(request));
    }
}
