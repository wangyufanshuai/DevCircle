package io.github.devcircle.content.application;

import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.seed.ThemeContentLibrary;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.DraftView;
import io.github.devcircle.content.api.FavoritePostView;
import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.PostCursorPage;
import io.github.devcircle.content.api.PostAuditView;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.api.ReadingProgressView;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class PostService {
    private final PostRepository postRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final List<PostSummary> seedPosts = ThemeContentLibrary.summaries();

    @Value("${devcircle.content.read-fallback-enabled:false}")
    private boolean readFallbackEnabled;

    public PostService(PostRepository postRepository, ReadingProgressRepository readingProgressRepository) {
        this.postRepository = postRepository;
        this.readingProgressRepository = readingProgressRepository;
    }

    public PageResponse<PostSummary> list(int page, int size, String type) {
        return list(page, size, type, null, null, null);
    }

    public PageResponse<PostSummary> list(int page, int size, String type, String theme, String contentKind, String difficulty) {
        int safeSize = Math.max(1, Math.min(size, 20));
        try {
            var records = filterByType(postRepository.list(page, safeSize, theme, contentKind, difficulty), type);
            return PageResponse.of(records, records.size(), page, safeSize);
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) {
                throw dependency(ex);
            }
            var records = filterByTheme(filterByType(seedPosts, type), theme, contentKind, difficulty);
            return PageResponse.of(records, records.size(), page, safeSize);
        }
    }

    public PostCursorPage listCursor(String cursor, int size, String type, String theme,
                                     String contentKind, String difficulty) {
        int safeSize = Math.max(1, Math.min(size, 20));
        return database(() -> postRepository.listCursor(cursor, safeSize, type, theme, contentKind, difficulty));
    }

    public PostSummary detail(Long id) {
        try {
            PostSummary post = postRepository.detail(id);
            if (!"PUBLISHED".equals(post.status())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
            }
            return post;
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) {
                throw dependency(ex);
            }
            return ThemeContentLibrary.find(id)
                    .map(seed -> seed.toSummary())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在"));
        }
    }

    public PostDetailView publicDetail(Long id, Long viewerId) {
        return database(() -> postRepository.publicDetail(id, viewerId));
    }

    public List<FavoritePostView> publicSummaries(List<Long> postIds) {
        return database(() -> postRepository.publicSummaries(postIds));
    }

    public ReadingProgressView saveReadingProgress(Long userId, Long postId, ReadingProgressRequest request,
                                                   String idempotencyKey) {
        requireWriteHeaders(userId, idempotencyKey);
        return database(() -> readingProgressRepository.save(userId, postId, request, idempotencyKey));
    }

    public List<PostSummary> related(Long id, int size) {
        try {
            return postRepository.related(id, size);
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) {
                throw dependency(ex);
            }
            return ThemeContentLibrary.related(id, size);
        }
    }

    public PostSummary create(Long authorId, CreatePostRequest request, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        return database(() -> postRepository.create(authorId, request, idempotencyKey));
    }

    public DraftView createDraft(Long authorId, SaveDraftRequest request, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        validateDraftMedia(request);
        return database(() -> postRepository.createDraft(authorId, request, idempotencyKey));
    }

    public List<DraftView> listDrafts(Long authorId, int size) {
        requireUser(authorId);
        return database(() -> postRepository.listDrafts(authorId, Math.max(1, Math.min(size, 50))));
    }

    public DraftView getDraft(Long authorId, Long draftId) {
        requireUser(authorId);
        return database(() -> postRepository.getDraft(authorId, draftId));
    }

    public List<PostAuditView> listAudits(Long authorId, Long draftId) {
        requireUser(authorId);
        return database(() -> postRepository.listAudits(authorId, draftId));
    }

    public DraftView updateDraft(Long authorId, Long draftId, SaveDraftRequest request, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "保存草稿必须携带 version");
        }
        validateDraftMedia(request);
        return database(() -> postRepository.updateDraft(authorId, draftId, request, idempotencyKey));
    }

    public DraftView submitDraft(Long authorId, Long draftId, long version, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        return database(() -> postRepository.submitDraft(authorId, draftId, version, idempotencyKey));
    }

    public Map<String, Object> createAppeal(Long userId, Long postId, String reason, String idempotencyKey) {
        requireWriteHeaders(userId, idempotencyKey);
        if (reason == null || reason.trim().length() < 4 || reason.trim().length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "申诉理由必须为 4 至 512 个字符");
        }
        String normalizedReason = reason.trim();
        return database(() -> postRepository.createAppeal(userId, postId, normalizedReason, idempotencyKey));
    }

    private void requireWriteHeaders(Long userId, String idempotencyKey) {
        requireUser(userId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供 Idempotency-Key");
        }
        if (idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 长度不能超过 128");
        }
    }

    private void validateDraftMedia(SaveDraftRequest request) {
        List<DraftMediaInput> media = request.media();
        if (media == null || media.isEmpty()) return;
        if (media.size() > 20) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每份草稿最多关联 20 个媒体");
        var ids = new HashSet<Long>();
        int covers = 0;
        for (DraftMediaInput item : media) {
            if (item == null || item.mediaId() == null || item.mediaId() <= 0 || !ids.add(item.mediaId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "媒体 ID 必须有效且不能重复");
            }
            String role = item.role() == null ? "INLINE" : item.role();
            if (!role.equals("COVER") && !role.equals("INLINE")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "媒体角色只能是 COVER 或 INLINE");
            }
            if (role.equals("COVER") && ++covers > 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每份草稿只能设置一张封面");
            }
            if (item.altText() == null || item.altText().isBlank() || item.altText().trim().length() > 240) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每个媒体都必须填写 1 至 240 个字符的替代文本");
            }
            if (item.sortOrder() != null && item.sortOrder() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "媒体排序不能为负数");
            }
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    private <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException ex) {
            throw dependency(ex);
        }
    }

    private BusinessException dependency(DataAccessException cause) {
        BusinessException failure = new BusinessException(
                ErrorCode.DEPENDENCY_UNAVAILABLE, "内容数据服务暂时不可用，请稍后重试");
        failure.initCause(cause);
        return failure;
    }

    private List<PostSummary> filterByType(List<PostSummary> posts, String type) {
        if (type == null || type.isBlank()) {
            return posts;
        }
        return posts.stream().filter(post -> post.type().equalsIgnoreCase(type)).toList();
    }

    private List<PostSummary> filterByTheme(List<PostSummary> posts, String theme, String contentKind, String difficulty) {
        return posts.stream()
                .filter(post -> theme == null || theme.isBlank() || post.theme().equalsIgnoreCase(theme))
                .filter(post -> contentKind == null || contentKind.isBlank() || post.contentKind().equalsIgnoreCase(contentKind))
                .filter(post -> difficulty == null || difficulty.isBlank() || post.difficulty().equalsIgnoreCase(difficulty))
                .toList();
    }
}
