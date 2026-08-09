package io.github.devcircle.content.infrastructure;

import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.PostMediaView;
import io.github.devcircle.content.contract.MediaAssetView;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and assembles media without owning post, draft, or moderation state. */
@Repository
public class PostMediaReadRepository {
    private final JdbcClient jdbcClient;

    public PostMediaReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public List<PostSummary> attachMediaAssets(List<PostSummary> posts) {
        if (posts.isEmpty()) {
            return posts;
        }
        Map<Long, List<MediaAssetView>> mediaByPostId = mediaAssetsByPostId(
                posts.stream().map(PostSummary::id).distinct().toList());
        return posts.stream()
                .map(post -> withMedia(post, mediaByPostId.getOrDefault(post.id(), List.of())))
                .toList();
    }

    public List<PostMediaView> publicMediaAssets(Long postId) {
        return jdbcClient.sql("""
                        SELECT m.id, m.object_key, m.url, m.content_type, m.size_bytes,
                               m.width, m.height, m.duration_seconds, m.status,
                               pm.role, pm.alt_text, pm.sort_order
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id = :postId AND m.status = 'APPROVED'
                        ORDER BY pm.sort_order, m.id
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new PostMediaView(
                        rs.getLong("id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                        (Integer) rs.getObject("duration_seconds"), rs.getString("status"),
                        rs.getString("role"), rs.getString("alt_text"), rs.getInt("sort_order")))
                .list();
    }

    public PostDetailView withPublicMedia(PostDetailView detail) {
        return new PostDetailView(
                detail.id(), detail.type(), detail.theme(), detail.contentKind(), detail.difficulty(),
                detail.solvable(), detail.solvedCount(), detail.title(), detail.excerpt(), detail.markdown(),
                detail.author(), detail.tags(), publicMediaAssets(detail.id()), detail.likes(), detail.comments(),
                detail.views(), detail.status(), detail.createdAt(), detail.clues(), detail.suspects(),
                detail.timeline(), detail.question(), detail.worldbuilding(), detail.cosmicFacts(),
                detail.relatedPostIds(), detail.viewerState(), detail.deductionState(), detail.solution());
    }

    private Map<Long, List<MediaAssetView>> mediaAssetsByPostId(List<Long> postIds) {
        List<PostMediaAsset> rows = jdbcClient.sql("""
                        SELECT pm.post_id, m.id, m.object_key, m.url, m.content_type, m.size_bytes,
                               m.width, m.height, m.duration_seconds, m.status
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id IN (:postIds)
                        ORDER BY pm.post_id, pm.sort_order, m.id
                        """)
                .param("postIds", postIds)
                .query((rs, rowNum) -> new PostMediaAsset(rs.getLong("post_id"), new MediaAssetView(
                        rs.getLong("id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                        (Integer) rs.getObject("duration_seconds"), rs.getString("status"))))
                .list();
        Map<Long, List<MediaAssetView>> result = new LinkedHashMap<>();
        for (PostMediaAsset row : rows) {
            result.computeIfAbsent(row.postId(), ignored -> new ArrayList<>()).add(row.media());
        }
        return result;
    }

    private PostSummary withMedia(PostSummary post, List<MediaAssetView> media) {
        return new PostSummary(
                post.id(), post.type(), post.theme(), post.contentKind(), post.difficulty(), post.solvable(),
                post.solvedCount(), post.title(), post.excerpt(), post.author(), post.authorId(), post.tags(), media,
                post.likes(), post.comments(), post.views(), post.status(), post.createdAt(), post.clues(),
                post.suspects(), post.timeline(), post.question(), post.answerKeywords(), post.explanation(),
                post.worldbuilding(), post.cosmicFacts(), post.relatedPostIds());
    }

    private record PostMediaAsset(Long postId, MediaAssetView media) {
    }
}
