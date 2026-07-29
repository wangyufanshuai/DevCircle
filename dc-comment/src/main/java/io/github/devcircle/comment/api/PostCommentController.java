package io.github.devcircle.comment.api;

import io.github.devcircle.comment.application.CommentService;
import io.github.devcircle.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
class PostCommentController {
    private final CommentService commentService;

    PostCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    ApiResponse<List<CommentThreadView>> list(@PathVariable Long postId,
                                             @RequestParam(required = false) Long cursor,
                                             @RequestParam(defaultValue = "20") int size) {
        List<CommentThreadView> threads = commentService.listThreads(postId, cursor, size);
        Long next = threads.isEmpty() ? null : threads.getLast().root().id();
        return ApiResponse.ok(threads, Map.of(
                "nextCursor", next == null ? "" : next.toString(),
                "hasMore", threads.size() == Math.max(1, Math.min(size, 50))
        ));
    }
}
