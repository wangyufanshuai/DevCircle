package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CommentThreadView;
import io.github.devcircle.comment.api.CommentView;
import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class CommentService {
    private final CommentRepository repository;

    public CommentService(CommentRepository repository) {
        this.repository = repository;
    }

    public List<CommentView> list(Long postId, Long beforeId, int size) {
        return repository.list(postId, beforeId, Math.max(1, Math.min(size, 51)));
    }

    public List<CommentThreadView> listThreads(Long postId, Long cursor, int size, String roles) {
        return repository.listThreads(postId, cursor, Math.max(1, Math.min(size, 51)), hasModeratorRole(roles));
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
        if (!hasModeratorRole(roles)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要审核权限");
        }
        requireKey(idempotencyKey);
        if (reason == null || reason.isBlank() || reason.trim().length() < 4 || reason.trim().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "隐藏评论必须填写 4 至 500 个字符的理由");
        }
        repository.hide(commentId, moderatorId, idempotencyKey, reason.trim());
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private boolean hasModeratorRole(String roles) {
        if (roles == null || roles.isBlank()) return false;
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(role -> role.equals("MODERATOR") || role.equals("ADMIN"));
    }
}
