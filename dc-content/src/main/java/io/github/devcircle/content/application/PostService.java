package io.github.devcircle.content.application;

import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PostService {
    private final PostRepository postRepository;
    private final List<PostSummary> demoPosts = List.of(
            new PostSummary(1001L, "TEXT", "DEDUCTION", "CASE_FILE", "HARD", true, 31,
                    "雾港钟楼失踪案", "凌晨零点钟声响起后，守夜人从封闭钟楼消失，只留下停在 00:07 的怀表、潮湿鞋印和一页被撕掉的航海日志。", "林墨",
                    List.of("密室", "时间线", "海港"), List.of(), 428, 86, 15800, "PUBLISHED", Instant.now().minusSeconds(7200)),
            new PostSummary(1002L, "IMAGE", "SCI_FI", "WORLDBUILDING", "MEDIUM", false, 0,
                    "木卫二冰下城市设定集", "多图拆解冰壳钻井、潮汐能源塔、低温生态穹顶和迁徙路线，适合科幻世界观共创。", "陈星",
                    List.of("世界观", "木卫二", "城市"), List.of(), 312, 44, 9360, "PENDING", Instant.now().minusSeconds(3600)),
            new PostSummary(1003L, "VIDEO", "COSMOS", "OBSERVATION_LOG", "EASY", false, 0,
                    "猎户臂边缘的一分钟观测日志", "用纵向视频记录星云光谱、脉冲星信号和无人探测器回传的异常闪烁。", "王遥",
                    List.of("观测日志", "星云", "探测器"), List.of(), 197, 28, 6100, "PUBLISHED", Instant.now().minusSeconds(900))
    );

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
            return demoPosts.stream()
                    .filter(post -> post.id().equals(id))
                    .findFirst()
                    .orElse(demoPosts.getFirst());
        }
    }

    public PostSummary create(Long authorId, CreatePostRequest request) {
        try {
            return postRepository.create(authorId, request);
        } catch (DataAccessException ex) {
            return fallbackCreate(request);
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
                request.title(), excerpt, "demo", request.tags() == null ? List.of() : request.tags(), List.of(), 0, 0, 0, "PENDING", Instant.now());
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
}
