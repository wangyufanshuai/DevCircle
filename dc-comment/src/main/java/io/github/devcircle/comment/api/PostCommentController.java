package io.github.devcircle.comment.api;

import io.github.devcircle.comment.application.CommentService;
import io.github.devcircle.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
public class PostCommentController {
    private final CommentService commentService;

    public PostCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    ApiResponse<List<CommentThreadView>> list(@PathVariable Long postId,
                                             @RequestHeader(value = "X-Roles", required = false) String roles,
                                             @RequestParam(required = false) Long cursor,
                                             @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.max(1, Math.min(size, 50));
        List<CommentThreadView> fetched = commentService.listThreads(postId, cursor, pageSize + 1, roles);
        boolean hasMore = fetched.size() > pageSize;
        List<CommentThreadView> threads = hasMore ? List.copyOf(fetched.subList(0, pageSize)) : fetched;
        Long next = hasMore && !threads.isEmpty() ? threads.getLast().root().id() : null;
        return ApiResponse.ok(threads, Map.of(
                "nextCursor", next == null ? "" : next.toString(), "hasMore", hasMore
        ));
    }
}
