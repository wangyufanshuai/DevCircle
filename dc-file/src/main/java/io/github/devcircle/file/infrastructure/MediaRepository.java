package io.github.devcircle.file.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.file.api.FileUploadResult;
import io.github.devcircle.file.api.UploadIntentRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Repository
public class MediaRepository {
    private static final String LEGACY_REQUEST_FINGERPRINT = "0".repeat(64);
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public MediaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Transactional
    public AssetIntent createIntent(Long userId, String idempotencyKey, String objectKey, String internalUrl,
                                    UploadIntentRequest request, Instant expiresAt) {
        Long existingId = claim(userId, "CREATE_UPLOAD", idempotencyKey,
                requestFingerprint("file-intent-v1", request.filename(), request.contentType(), request.size(),
                        request.width(), request.height(), request.durationSeconds()));
        if (existingId != null) {
            return requireOwned(userId, existingId);
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO media_asset(uploader_id, object_key, url, content_type, size_bytes, expected_size,
                                            width, height, duration_seconds, status, upload_status, upload_expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_REVIEW', 'UPLOADING', ?)
                    """, new String[]{"id"});
            statement.setLong(1, userId);
            statement.setString(2, objectKey);
            statement.setString(3, internalUrl);
            statement.setString(4, request.contentType());
            statement.setLong(5, request.size());
            statement.setLong(6, request.size());
            statement.setObject(7, request.width());
            statement.setObject(8, request.height());
            statement.setObject(9, request.durationSeconds());
            statement.setTimestamp(10, Timestamp.from(expiresAt));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create upload intent");
        }
        Long mediaId = key.longValue();
        attach(idempotencyKey, mediaId);
        return requireOwned(userId, mediaId);
    }

    public AssetIntent requireOwned(Long userId, Long mediaId) {
        List<AssetIntent> assets = jdbcClient.sql("""
                        SELECT id, uploader_id, object_key, url, content_type, expected_size,
                               width, height, duration_seconds, status, upload_status, upload_expires_at
                        FROM media_asset WHERE id = :mediaId AND uploader_id = :userId
                        """)
                .param("mediaId", mediaId)
                .param("userId", userId)
                .query((rs, rowNum) -> new AssetIntent(
                        rs.getLong("id"), rs.getLong("uploader_id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("expected_size"), (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"), (Integer) rs.getObject("duration_seconds"),
                        rs.getString("status"), rs.getString("upload_status"),
                        rs.getTimestamp("upload_expires_at") == null ? null : rs.getTimestamp("upload_expires_at").toInstant()))
                .list();
        if (assets.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传任务不存在");
        }
        return assets.getFirst();
    }

    public AssetIntent requirePublic(Long mediaId) {
        return jdbcClient.sql("""
                        SELECT DISTINCT m.id, m.uploader_id, m.object_key, m.url, m.content_type, m.expected_size,
                               m.width, m.height, m.duration_seconds, m.status, m.upload_status, m.upload_expires_at
                        FROM media_asset m
                        JOIN post_media pm ON pm.media_id = m.id
                        JOIN post p ON p.id = pm.post_id
                        WHERE m.id = :mediaId AND m.status = 'APPROVED'
                          AND m.upload_status = 'COMPLETED' AND p.status = 'PUBLISHED'
                        """)
                .param("mediaId", mediaId)
                .query((rs, rowNum) -> new AssetIntent(
                        rs.getLong("id"), rs.getLong("uploader_id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("expected_size"), (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"), (Integer) rs.getObject("duration_seconds"),
                        rs.getString("status"), rs.getString("upload_status"),
                        rs.getTimestamp("upload_expires_at") == null ? null : rs.getTimestamp("upload_expires_at").toInstant()))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "媒体不存在或尚未公开"));
    }

    public List<AssetIntent> requirePublicBatch(List<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) return List.of();
        List<Long> safeIds = mediaIds.stream().filter(id -> id != null && id > 0).distinct().limit(50).toList();
        if (safeIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                        SELECT DISTINCT m.id, m.uploader_id, m.object_key, m.url, m.content_type, m.expected_size,
                               m.width, m.height, m.duration_seconds, m.status, m.upload_status, m.upload_expires_at
                        FROM media_asset m
                        JOIN post_media pm ON pm.media_id = m.id
                        JOIN post p ON p.id = pm.post_id
                        WHERE m.id IN (:mediaIds) AND m.status = 'APPROVED'
                          AND m.upload_status = 'COMPLETED' AND p.status = 'PUBLISHED'
                        ORDER BY m.id
                        """)
                .param("mediaIds", safeIds)
                .query((rs, rowNum) -> new AssetIntent(
                        rs.getLong("id"), rs.getLong("uploader_id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("expected_size"), (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"), (Integer) rs.getObject("duration_seconds"),
                        rs.getString("status"), rs.getString("upload_status"),
                        rs.getTimestamp("upload_expires_at") == null ? null : rs.getTimestamp("upload_expires_at").toInstant()))
                .list();
    }

    @Transactional
    public FileUploadResult complete(Long userId, Long mediaId, String idempotencyKey, long actualSize) {
        Long duplicate = claim(userId, "COMPLETE_UPLOAD:" + mediaId, idempotencyKey,
                requestFingerprint("file-complete-v1", mediaId, actualSize));
        AssetIntent asset = requireOwned(userId, mediaId);
        if (duplicate != null || "COMPLETED".equals(asset.uploadStatus())) {
            return toResult(asset, actualSize);
        }
        int updated = jdbcClient.sql("""
                        UPDATE media_asset SET size_bytes = :actualSize, upload_status = 'COMPLETED'
                        WHERE id = :mediaId AND uploader_id = :userId AND upload_status = 'UPLOADING'
                        """)
                .param("actualSize", actualSize)
                .param("mediaId", mediaId)
                .param("userId", userId)
                .update();
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上传任务状态不允许完成");
        }
        attach(idempotencyKey, mediaId);
        return toResult(requireOwned(userId, mediaId), actualSize);
    }

    public long count() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM media_asset").query(Long.class).single();
        return count == null ? 0 : count;
    }

    private FileUploadResult toResult(AssetIntent asset, long actualSize) {
        return new FileUploadResult(asset.id(), asset.objectKey(), "", asset.contentType(), actualSize,
                asset.width(), asset.height(), asset.durationSeconds(), asset.status());
    }

    private Long claim(Long userId, String operation, String key, String requestFingerprint) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO file_idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES (:key, :userId, :operation, :requestFingerprint)
                        """)
                .param("key", key).param("userId", userId).param("operation", operation)
                .param("requestFingerprint", requestFingerprint).update();
        if (inserted == 1) return null;
        Idempotency existing = jdbcClient.sql("""
                        SELECT user_id, operation, request_fingerprint, resource_id
                        FROM file_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new Idempotency(
                        rs.getLong("user_id"), rs.getString("operation"), rs.getString("request_fingerprint"),
                        (Long) rs.getObject("resource_id")))
                .single();
        boolean legacyRecord = LEGACY_REQUEST_FINGERPRINT.equals(existing.requestFingerprint());
        if (!existing.userId().equals(userId) || !existing.operation().equals(operation)
                || (!legacyRecord && !existing.requestFingerprint().equals(requestFingerprint))) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                    "Idempotency-Key 已被其他上传操作或请求载荷使用");
        }
        if (existing.resourceId() == null) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同上传请求正在处理中");
        }
        return existing.resourceId();
    }

    private String requestFingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                canonical.append("-1:|");
            } else {
                String text = String.valueOf(value);
                canonical.append(text.length()).append(':').append(text).append('|');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void attach(String key, Long mediaId) {
        jdbcClient.sql("UPDATE file_idempotency_record SET resource_id = :mediaId WHERE idempotency_key = :key")
                .param("mediaId", mediaId).param("key", key).update();
    }

    public record AssetIntent(Long id, Long uploaderId, String objectKey, String internalUrl, String contentType,
                              long expectedSize, Integer width, Integer height, Integer durationSeconds,
                              String status, String uploadStatus, Instant expiresAt) {
    }

    private record Idempotency(Long userId, String operation, String requestFingerprint, Long resourceId) {
    }
}
