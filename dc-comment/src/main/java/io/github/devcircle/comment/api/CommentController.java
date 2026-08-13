package io.github.devcircle.comment.api;

import io.github.devcircle.comment.application.CommentService;
import io.github.devcircle.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/comments")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    ApiResponse<List<CommentView>> list(@RequestParam Long postId,
                                        @RequestParam(required = false) Long beforeId,
                                        @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.max(1, Math.min(size, 50));
        List<CommentView> fetched = commentService.list(postId, beforeId, pageSize + 1);
        boolean hasMore = fetched.size() > pageSize;
        List<CommentView> comments = hasMore ? List.copyOf(fetched.subList(0, pageSize)) : fetched;
        Long next = hasMore && !comments.isEmpty() ? comments.getLast().id() : null;
        return ApiResponse.ok(comments, Map.of("nextCursor", next == null ? "" : String.valueOf(next), "hasMore", hasMore));
    }

    @PostMapping
    ApiResponse<CommentView> create(@RequestHeader("X-User-Id") Long userId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.ok(commentService.create(userId, idempotencyKey, request));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId,
                             @RequestHeader("Idempotency-Key") String idempotencyKey) {
        commentService.deleteOwn(id, userId, idempotencyKey);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/hide")
    ApiResponse<Void> hide(@PathVariable Long id,
                           @RequestHeader("X-User-Id") Long moderatorId,
                           @RequestHeader("X-Roles") String roles,
                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                           @RequestBody(required = false) Map<String, String> body) {
        commentService.hide(id, moderatorId, roles, idempotencyKey, body == null ? null : body.get("reason"));
        return ApiResponse.ok();
    }
}
