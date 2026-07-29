package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CommentView;
import io.github.devcircle.comment.api.CommentThreadView;
import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommentService {
    private final CommentRepository repository;

    public CommentService(CommentRepository repository) {
        this.repository = repository;
    }

    public List<CommentView> list(Long postId, Long beforeId, int size) {
        return repository.list(postId, beforeId, size);
    }

    public List<CommentThreadView> listThreads(Long postId, Long cursor, int size) {
        return repository.listThreads(postId, cursor, Math.max(1, Math.min(size, 50)));
    }

    public CommentView create(Long userId, String idempotencyKey, CreateCommentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
        return repository.create(userId, request.postId(), request.parentId(), request.content(), idempotencyKey);
    }

    public void deleteOwn(Long commentId, Long userId, String idempotencyKey) {
        requireKey(idempotencyKey);
        repository.deleteOwn(commentId, userId, idempotencyKey);
    }

    public void hide(Long commentId, Long moderatorId, String roles, String idempotencyKey, String reason) {
        if (roles == null || (!roles.contains("MODERATOR") && !roles.contains("ADMIN"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要审核权限");
        }
        requireKey(idempotencyKey);
        repository.hide(commentId, moderatorId, idempotencyKey, reason);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }
}
