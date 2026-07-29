package io.github.devcircle.file.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.file.infrastructure.MediaRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
class FileController {
    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "video/mp4");
    private static final Map<String, Set<String>> ALLOWED_EXTENSIONS = Map.of(
            "image/png", Set.of("png"), "image/jpeg", Set.of("jpg", "jpeg"),
            "image/webp", Set.of("webp"), "video/mp4", Set.of("mp4"));

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final MediaRepository mediaRepository;
    private final String bucket;
    private final String internalBaseUrl;
    private final long maxBytes;
    private final Duration intentTtl;

    FileController(S3Client s3Client, S3Presigner presigner, MediaRepository mediaRepository,
                   @Value("${devcircle.s3.bucket}") String bucket,
                   @Value("${devcircle.s3.public-base-url}") String internalBaseUrl,
                   @Value("${devcircle.upload.max-bytes:10485760}") long maxBytes,
                   @Value("${devcircle.upload.intent-ttl-minutes:15}") long intentTtlMinutes) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.mediaRepository = mediaRepository;
        this.bucket = bucket;
        this.internalBaseUrl = internalBaseUrl;
        this.maxBytes = maxBytes;
        this.intentTtl = Duration.ofMinutes(Math.max(1, Math.min(intentTtlMinutes, 60)));
    }

    @PostMapping("/intents")
    ApiResponse<UploadIntentResult> createIntent(@RequestHeader("X-User-Id") Long userId,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody UploadIntentRequest input) {
        requireKey(idempotencyKey);
        String contentType = normalizeContentType(input.contentType());
        validateMetadata(input.filename(), contentType, input.size());
        ensureBucket();
        Instant expiresAt = Instant.now().plus(intentTtl);
        String objectKey = "private/uploads/" + userId + "/" + UUID.randomUUID() + "-" + sanitizeFilename(input.filename());
        UploadIntentRequest request = new UploadIntentRequest(input.filename(), contentType, input.size(),
                input.width(), input.height(), input.durationSeconds());
        MediaRepository.AssetIntent asset = mediaRepository.createIntent(userId, idempotencyKey, objectKey,
                internalBaseUrl.replaceAll("/$", "") + "/" + objectKey, request, expiresAt);
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(bucket).key(asset.objectKey()).contentType(asset.contentType()).contentLength(asset.expectedSize()).build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(intentTtl).putObjectRequest(putObject).build());
        return ApiResponse.ok(new UploadIntentResult(asset.id(), asset.objectKey(), signed.url().toString(),
                Map.of("Content-Type", asset.contentType()), expiresAt));
    }

    @PostMapping("/{id}/complete")
    ApiResponse<FileUploadResult> complete(@RequestHeader("X-User-Id") Long userId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @PathVariable Long id) {
        requireKey(idempotencyKey);
        MediaRepository.AssetIntent asset = mediaRepository.requireOwned(userId, id);
        if (asset.expiresAt() != null && asset.expiresAt().isBefore(Instant.now()) && !"COMPLETED".equals(asset.uploadStatus())) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "上传凭证已过期，请重新申请");
        }
        var head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(asset.objectKey()).build());
        try {
            if (head.contentLength() != asset.expectedSize() || head.contentLength() <= 0 || head.contentLength() > maxBytes) {
                throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "上传文件大小与申请信息不一致");
            }
            if (!asset.contentType().equalsIgnoreCase(normalizeContentType(head.contentType()))) {
                throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "上传文件 MIME 类型与申请信息不一致");
            }
            byte[] prefix = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(asset.objectKey()).range("bytes=0-15").build()).asByteArray();
            if (!magicMatches(asset.contentType(), prefix)) {
                throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "文件头校验失败，上传内容与声明类型不一致");
            }
        } catch (BusinessException ex) {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(asset.objectKey()).build());
            throw ex;
        }
        return ApiResponse.ok(mediaRepository.complete(userId, id, idempotencyKey, head.contentLength()));
    }

    private void validateMetadata(String filename, String contentType, long size) {
        if (size <= 0 || size > maxBytes) throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "文件为空或超过上传大小限制");
        if (!ALLOWED_TYPES.contains(contentType)) throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "仅允许 PNG、JPEG、WebP 图片或 MP4 视频");
        if (!ALLOWED_EXTENSIONS.get(contentType).contains(extension(filename))) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "文件扩展名与 MIME 类型不匹配");
        }
    }

    private boolean magicMatches(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, 0x89, 0x50, 0x4E, 0x47);
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/webp" -> bytes.length >= 12 && startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                    && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50;
            case "video/mp4" -> bytes.length >= 12 && bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70;
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((bytes[i] & 0xFF) != magic[i]) return false;
        return true;
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private String normalizeContentType(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private String extension(String filename) {
        return filename == null || !filename.contains(".") ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    private String sanitizeFilename(String filename) { return filename.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    private void ensureBucket() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
        }
    }
}
