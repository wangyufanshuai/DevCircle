package io.github.devcircle.content.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.content.application.PostService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
class PostController {
    private final PostService postService;
    private final PostDetailComposer postDetailComposer;

    PostController(PostService postService, ObjectProvider<PostDetailComposer> postDetailComposers) {
        this.postService = postService;
        this.postDetailComposer = postDetailComposers.getIfAvailable(PostDetailComposer::passthrough);
    }

    @GetMapping
    ApiResponse<?> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String theme,
                                                @RequestParam(required = false) String contentKind,
                                                @RequestParam(required = false) String difficulty) {
        if (cursor != null) {
            PostCursorPage result = postService.listCursor(cursor, size, type, theme, contentKind, difficulty);
            return ApiResponse.ok(result.records(), Map.of("nextCursor", result.nextCursor(), "hasMore", result.hasMore()));
        }
        return ApiResponse.ok(postService.list(page, size, type, theme, contentKind, difficulty));
    }

    @GetMapping("/{id}")
    ApiResponse<PostDetailView> detail(@PathVariable Long id,
                                       @RequestHeader(value = "X-User-Id", required = false) Long viewerId,
                                       @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                       String authorization) {
        PostDetailView detail = postService.publicDetail(id, viewerId);
        return ApiResponse.ok(postDetailComposer.compose(detail, viewerId, traceId, authorization));
    }

    @GetMapping("/summaries")
    ApiResponse<List<FavoritePostView>> summaries(@RequestParam List<Long> ids) {
        return ApiResponse.ok(postService.publicSummaries(ids));
    }

    @GetMapping("/{id}/related")
    ApiResponse<List<PostSummary>> related(@PathVariable Long id,
                                           @RequestParam(defaultValue = "6") int size) {
        return ApiResponse.ok(postService.related(id, Math.min(size, 12)));
    }

    @PostMapping
    ApiResponse<PostSummary> create(@RequestHeader("X-User-Id") Long userId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(postService.create(userId, request, idempotencyKey));
    }

    @PostMapping("/drafts")
    ApiResponse<DraftView> createDraft(@RequestHeader("X-User-Id") Long userId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody SaveDraftRequest request) {
        return ApiResponse.ok(postService.createDraft(userId, request, idempotencyKey));
    }

    @GetMapping("/drafts")
    ApiResponse<List<DraftView>> listDrafts(@RequestHeader("X-User-Id") Long userId,
                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(postService.listDrafts(userId, size));
    }

    @GetMapping("/drafts/{id}")
    ApiResponse<DraftView> getDraft(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable Long id) {
        return ApiResponse.ok(postService.getDraft(userId, id));
    }

    @GetMapping("/drafts/{id}/audits")
    ApiResponse<List<PostAuditView>> audits(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return ApiResponse.ok(postService.listAudits(userId, id));
    }

    @PutMapping("/drafts/{id}")
    ApiResponse<DraftView> updateDraft(@RequestHeader("X-User-Id") Long userId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @PathVariable Long id,
                                       @Valid @RequestBody SaveDraftRequest request) {
        return ApiResponse.ok(postService.updateDraft(userId, id, request, idempotencyKey));
    }

    @PostMapping("/drafts/{id}/submit")
    ApiResponse<DraftView> submitDraft(@RequestHeader("X-User-Id") Long userId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @PathVariable Long id,
                                       @RequestParam long version) {
        return ApiResponse.ok(postService.submitDraft(userId, id, version, idempotencyKey));
    }

    @PostMapping("/{id}/appeals")
    ApiResponse<Map<String, Object>> appeal(@RequestHeader("X-User-Id") Long userId,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @PathVariable Long id,
                                            @Valid @RequestBody AppealRequest request) {
        return ApiResponse.ok(postService.createAppeal(userId, id, request.reason(), idempotencyKey));
    }

    @PutMapping("/{id}/reading-progress")
    ApiResponse<ReadingProgressView> readingProgress(@RequestHeader("X-User-Id") Long userId,
                                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody ReadingProgressRequest request) {
        return ApiResponse.ok(postService.saveReadingProgress(userId, id, request, idempotencyKey));
    }
}
