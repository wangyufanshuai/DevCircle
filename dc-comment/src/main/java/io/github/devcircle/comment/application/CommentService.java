package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CommentThreadView;
import io.github.devcircle.comment.api.CommentView;
import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

@Service
public class CommentService {
    private final CommentRepository repository;
    private final PostPublicationGateway postPublicationGateway;

    @Autowired
    public CommentService(CommentRepository repository, PostPublicationGateway postPublicationGateway) {
        this.repository = repository;
        this.postPublicationGateway = postPublicationGateway;
    }

    /** Compatibility constructor for isolated unit tests and older embedders. */
    public CommentService(CommentRepository repository) {
        this(repository, postId -> { });
    }

    public List<CommentView> list(Long postId, Long beforeId, int size) {
        requirePostId(postId);
        return repository.list(postId, beforeId, Math.max(1, Math.min(size, 51)));
    }

    public List<CommentThreadView> listThreads(Long postId, Long cursor, int size, String roles) {
        requirePostId(postId);
        return repository.listThreads(postId, cursor, Math.max(1, Math.min(size, 51)), hasModeratorRole(roles));
    }

    public CommentView create(Long userId, String idempotencyKey, CreateCommentRequest request) {
        requireUser(userId);
        requireKey(idempotencyKey);
        requireCreateRequest(request);
        postPublicationGateway.requirePublished(request.postId());
        return repository.create(userId, request.postId(), request.parentId(), request.content(), idempotencyKey);
    }

    public void deleteOwn(Long commentId, Long userId, String idempotencyKey) {
        requireUser(userId);
        requireCommentId(commentId);
        requireKey(idempotencyKey);
        repository.deleteOwn(commentId, userId, idempotencyKey);
    }

    public void hide(Long commentId, Long moderatorId, String roles, String idempotencyKey, String reason) {
        requireUser(moderatorId);
        requireCommentId(commentId);
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

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    private void requirePostId(Long postId) {
        if (postId == null || postId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "内容标识无效");
        }
    }

    private void requireCommentId(Long commentId) {
        if (commentId == null || commentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "评论标识无效");
        }
    }

    private void requireCreateRequest(CreateCommentRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "评论请求不能为空");
        }
        requirePostId(request.postId());
        if (request.parentId() != null && request.parentId() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "回复目标无效");
        }
        if (request.content() == null || request.content().isBlank() || request.content().length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "评论内容应为 1 至 2000 个字符");
        }
    }

    private boolean hasModeratorRole(String roles) {
        if (roles == null || roles.isBlank()) return false;
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(role -> role.equals("MODERATOR") || role.equals("ADMIN"));
    }
}
