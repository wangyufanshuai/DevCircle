package io.github.devcircle.content.application;

import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PostService {
    private final PostRepository postRepository;
    private final List<PostSummary> demoPosts = ThemeContentLibrary.summaries();

    public PostService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    public PageResponse<PostSummary> list(int page, int size, String type) {
        return list(page, size, type, null, null, null);
    }

    public PageResponse<PostSummary> list(int page, int size, String type, String theme, String contentKind, String difficulty) {
        try {
            var records = filterByType(postRepository.list(page, size, theme, contentKind, difficulty), type);
            return PageResponse.of(records, records.size(), page, size);
        } catch (DataAccessException ex) {
            var records = filterByTheme(filterByType(demoPosts, type), theme, contentKind, difficulty);
            return PageResponse.of(records, records.size(), page, size);
        }
    }

    public PostSummary detail(Long id) {
        try {
            return postRepository.detail(id);
        } catch (DataAccessException ex) {
            return ThemeContentLibrary.find(id).map(seed -> seed.toSummary()).orElse(demoPosts.getFirst());
        }
    }

    public List<PostSummary> related(Long id, int size) {
        try {
            var records = postRepository.related(id, size);
            return records.isEmpty() ? ThemeContentLibrary.related(id, size) : records;
        } catch (DataAccessException ex) {
            return ThemeContentLibrary.related(id, size);
        }
    }

    public PostSummary create(Long authorId, CreatePostRequest request) {
        try {
            return postRepository.create(authorId, request);
        } catch (DataAccessException ex) {
            return fallbackCreate(request);
        }
    }

    public Map<String, Object> createAppeal(Long userId, Long postId, String reason) {
        try {
            return postRepository.createAppeal(userId, postId, reason);
        } catch (DataAccessException ex) {
            return Map.of("appealId", System.currentTimeMillis(), "postId", postId, "status", "PENDING", "degraded", true);
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

    private PostSummary fallbackCreate(CreatePostRequest request) {
        String excerpt = request.content().substring(0, Math.min(60, request.content().length()));
        return new PostSummary(2001L, normalizeType(request.type()), normalizeTheme(request.theme()), normalizeContentKind(request.contentKind()),
                normalizeDifficulty(request.difficulty()), Boolean.TRUE.equals(request.solvable()), 0,
                request.title(), excerpt, "demo", request.tags() == null ? List.of() : request.tags(), List.of(), 0, 0, 0, "PENDING", java.time.Instant.now(),
                splitDetails(request.clues()), List.of(), List.of(), Boolean.TRUE.equals(request.solvable()) ? "请根据线索提交你的推理结论。" : "", splitDetails(request.solution()), request.solution() == null ? "" : request.solution(), List.of(), List.of(), List.of());
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "TEXT" : type.toUpperCase();
    }

    private String normalizeTheme(String theme) {
        return theme == null || theme.isBlank() ? "DEDUCTION" : theme.toUpperCase();
    }

    private String normalizeContentKind(String contentKind) {
        return contentKind == null || contentKind.isBlank() ? "CASE_FILE" : contentKind.toUpperCase();
    }

    private String normalizeDifficulty(String difficulty) {
        return difficulty == null || difficulty.isBlank() ? "MEDIUM" : difficulty.toUpperCase();
    }

    private List<String> splitDetails(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("\\R|;|；"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
