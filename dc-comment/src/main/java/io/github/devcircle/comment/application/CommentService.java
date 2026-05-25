package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CommentView;
import io.github.devcircle.comment.api.CreateCommentRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class CommentService {
    public List<CommentView> list(Long postId) {
        return List.of(
                new CommentView(501L, postId, null, "zhou", "这个 Outbox 表建议加状态索引和补偿任务。", "VISIBLE", Instant.now().minusSeconds(1800)),
                new CommentView(502L, postId, 501L, "lin", "同意，计划放到可靠事件模块。", "VISIBLE", Instant.now().minusSeconds(900))
        );
    }

    public CommentView create(CreateCommentRequest request) {
        return new CommentView(9001L, request.postId(), request.parentId(), "demo", request.content(), "PENDING", Instant.now());
    }
}

