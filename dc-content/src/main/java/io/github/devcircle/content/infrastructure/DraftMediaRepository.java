package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.DraftMediaView;
import io.github.devcircle.content.api.DraftView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DraftMediaRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public DraftMediaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public void replace(Long draftId, Long authorId, List<Long> mediaIds, List<DraftMediaInput> media) {
        jdbcClient.sql("DELETE FROM post_media WHERE post_id = :postId").param("postId", draftId).update();
        List<DraftMediaInput> items = media == null || media.isEmpty()
                ? safeMediaIds(mediaIds).stream().distinct().limit(20)
                    .map(id -> new DraftMediaInput(id, "INLINE", null, null)).toList()
                : media.stream().limit(20).toList();
        int sortOrder = 0;
        for (DraftMediaInput item : items) {
            requireOwnedCompleted(authorId, item.mediaId());
            String role = "COVER".equals(item.role()) ? "COVER" : "INLINE";
            String altText = item.altText() == null ? null : item.altText().trim();
            jdbcClient.sql("""
                            INSERT INTO post_media(post_id, media_id, role, alt_text, sort_order)
                            VALUES (:postId, :mediaId, :role, :altText, :sortOrder)
                            """)
                    .param("postId", draftId).param("mediaId", item.mediaId()).param("role", role)
                    .param("altText", altText).param("sortOrder", sortOrder++).update();
        }
    }

    public List<DraftView> attach(List<DraftView> drafts) {
        if (drafts.isEmpty()) return drafts;
        List<Long> postIds = drafts.stream().map(DraftView::id).toList();
        List<DraftMediaRow> rows = jdbcClient.sql("""
                        SELECT pm.post_id, pm.media_id, pm.role, pm.alt_text, pm.sort_order, m.content_type
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id IN (:postIds)
                        ORDER BY pm.post_id, pm.sort_order, pm.media_id
                        """)
                .param("postIds", postIds)
                .query((rs, rowNum) -> new DraftMediaRow(rs.getLong("post_id"),
                        new DraftMediaView(rs.getLong("media_id"), rs.getString("role"),
                                safeText(rs.getString("alt_text")), rs.getInt("sort_order"),
                                rs.getString("content_type"))))
                .list();
        Map<Long, List<DraftMediaView>> mediaByPost = new LinkedHashMap<>();
        for (DraftMediaRow row : rows) {
            mediaByPost.computeIfAbsent(row.postId(), ignored -> new ArrayList<>()).add(row.media());
        }
        return drafts.stream().map(draft -> draft.withMedia(mediaByPost.getOrDefault(draft.id(), List.of()))).toList();
    }

    public void requireOwnedCompleted(Long authorId, Long mediaId) {
        requireUploadContract();
        Integer owned = jdbcClient.sql("""
                        SELECT COUNT(*) FROM media_asset
                        WHERE id = :mediaId AND uploader_id = :authorId AND upload_status = 'COMPLETED'
                        """)
                .param("mediaId", mediaId).param("authorId", authorId).query(Integer.class).single();
        if (owned == null || owned == 0) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "媒体未完成上传或不属于当前作者");
        }
    }

    public void requireSubmittable(Long authorId, Long draftId) {
        Long attachmentCount = jdbcClient.sql("SELECT COUNT(*) FROM post_media WHERE post_id = :draftId")
                .param("draftId", draftId)
                .query(Long.class)
                .single();
        if (attachmentCount == null || attachmentCount == 0) return;
        requireUploadContract();
        SubmissionMediaState state = jdbcClient.sql("""
                        SELECT COUNT(*) AS attachment_count,
                               COALESCE(SUM(CASE WHEN m.id IS NOT NULL AND m.uploader_id = :authorId
                                                   AND m.upload_status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS valid_count,
                               COALESCE(SUM(CASE WHEN pm.alt_text IS NULL OR TRIM(pm.alt_text) = '' THEN 1 ELSE 0 END), 0) AS missing_alt,
                               COALESCE(SUM(CASE WHEN pm.role NOT IN ('COVER', 'INLINE') THEN 1 ELSE 0 END), 0) AS invalid_role,
                               COALESCE(SUM(CASE WHEN pm.role = 'COVER' THEN 1 ELSE 0 END), 0) AS cover_count
                        FROM post_media pm
                        LEFT JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id = :draftId
                        """)
                .param("authorId", authorId)
                .param("draftId", draftId)
                .query((rs, rowNum) -> new SubmissionMediaState(
                        rs.getLong("attachment_count"), rs.getLong("valid_count"),
                        rs.getLong("missing_alt"), rs.getLong("invalid_role"), rs.getLong("cover_count")))
                .single();
        if (state.attachmentCount() != state.validCount()) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "投稿包含未完成上传或不属于当前作者的媒体");
        }
        if (state.missingAlt() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请为每个媒体填写替代文本后再投稿");
        }
        if (state.invalidRole() > 0 || state.coverCount() > 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "媒体角色无效，请重新设置后再投稿");
        }
    }

    private void requireUploadContract() {
        Boolean ready = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                columnExists(connection, "media_asset", "uploader_id")
                        && columnExists(connection, "media_asset", "upload_status"));
        if (!Boolean.TRUE.equals(ready)) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "媒体上传服务尚未完成初始化，请稍后重试");
        }
    }

    private boolean columnExists(java.sql.Connection connection, String table, String column) throws SQLException {
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        for (String tableName : List.of(table, table.toUpperCase())) {
            for (String columnName : List.of(column, column.toUpperCase())) {
                try (ResultSet result = connection.getMetaData()
                        .getColumns(catalog, schema, tableName, columnName)) {
                    if (result.next()) return true;
                }
                if (schema != null) {
                    try (ResultSet result = connection.getMetaData()
                            .getColumns(catalog, null, tableName, columnName)) {
                        if (result.next()) return true;
                    }
                }
            }
        }
        return false;
    }

    private List<Long> safeMediaIds(List<Long> mediaIds) {
        return mediaIds == null ? List.of() : mediaIds;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private record DraftMediaRow(Long postId, DraftMediaView media) {
    }

    private record SubmissionMediaState(long attachmentCount, long validCount, long missingAlt,
                                        long invalidRole, long coverCount) {
    }
}
