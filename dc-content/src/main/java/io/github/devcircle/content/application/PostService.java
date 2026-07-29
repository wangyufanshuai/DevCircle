package io.github.devcircle.content.application;

import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.DraftView;
import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.PostCursorPage;
import io.github.devcircle.content.api.PostAuditView;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.api.ReadingProgressView;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PostService {
    private final PostRepository postRepository;
    private final List<PostSummary> seedPosts = ThemeContentLibrary.summaries();

    @Value("${devcircle.content.read-fallback-enabled:false}")
    private boolean readFallbackEnabled;

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
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
                throw ex;
            }
            var records = filterByTheme(filterByType(seedPosts, type), theme, contentKind, difficulty);
            return PageResponse.of(records, records.size(), page, safeSize);
        }
    }

    public PostCursorPage listCursor(String cursor, int size, String type, String theme,
                                     String contentKind, String difficulty) {
        int safeSize = Math.max(1, Math.min(size, 20));
        PostCursorPage page = postRepository.listCursor(cursor, safeSize, theme, contentKind, difficulty);
        return new PostCursorPage(filterByType(page.records(), type), page.nextCursor(), page.hasMore());
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
                throw ex;
            }
            return ThemeContentLibrary.find(id)
                    .map(seed -> seed.toSummary())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在"));
        }
    }

    public PostDetailView publicDetail(Long id, Long viewerId) {
        return postRepository.publicDetail(id, viewerId);
    }

    public ReadingProgressView saveReadingProgress(Long userId, Long postId, ReadingProgressRequest request,
                                                   String idempotencyKey) {
        requireWriteHeaders(userId, idempotencyKey);
        return postRepository.saveReadingProgress(userId, postId, request, idempotencyKey);
    }

    public List<PostSummary> related(Long id, int size) {
        try {
            return postRepository.related(id, size);
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) {
                throw ex;
            }
            return ThemeContentLibrary.related(id, size);
        }
    }

    public PostSummary create(Long authorId, CreatePostRequest request, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        return postRepository.create(authorId, request, idempotencyKey);
    }

    public DraftView createDraft(Long authorId, SaveDraftRequest request, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        return postRepository.createDraft(authorId, request, idempotencyKey);
    }

    public List<DraftView> listDrafts(Long authorId, int size) {
        requireUser(authorId);
        return postRepository.listDrafts(authorId, Math.max(1, Math.min(size, 50)));
    }

    public DraftView getDraft(Long authorId, Long draftId) {
        requireUser(authorId);
        return postRepository.getDraft(authorId, draftId);
    }

    public List<PostAuditView> listAudits(Long authorId, Long draftId) {
        requireUser(authorId);
        return postRepository.listAudits(authorId, draftId);
    }

    public DraftView updateDraft(Long authorId, Long draftId, SaveDraftRequest request, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "保存草稿必须携带 version");
        }
        return postRepository.updateDraft(authorId, draftId, request, idempotencyKey);
    }

    public DraftView submitDraft(Long authorId, Long draftId, long version, String idempotencyKey) {
        requireWriteHeaders(authorId, idempotencyKey);
        return postRepository.submitDraft(authorId, draftId, version, idempotencyKey);
    }

    public Map<String, Object> createAppeal(Long userId, Long postId, String reason, String idempotencyKey) {
        requireWriteHeaders(userId, idempotencyKey);
        return postRepository.createAppeal(userId, postId, reason, idempotencyKey);
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

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
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
