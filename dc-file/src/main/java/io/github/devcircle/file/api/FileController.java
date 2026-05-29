package io.github.devcircle.file.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.file.infrastructure.MediaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
class FileController {
    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "video/mp4");
    private static final Map<String, Set<String>> ALLOWED_EXTENSIONS = Map.of(
            "image/png", Set.of("png"),
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/webp", Set.of("webp"),
            "video/mp4", Set.of("mp4")
    );

    private final S3Client s3Client;
    private final MediaRepository mediaRepository;
    private final String bucket;
    private final String publicBaseUrl;
    private final long maxBytes;

    FileController(S3Client s3Client,
                   MediaRepository mediaRepository,
                   @Value("${devcircle.s3.bucket}") String bucket,
                   @Value("${devcircle.s3.public-base-url}") String publicBaseUrl,
                   @Value("${devcircle.upload.max-bytes:10485760}") long maxBytes) {
        this.s3Client = s3Client;
        this.mediaRepository = mediaRepository;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
        this.maxBytes = maxBytes;
    }

    @PostMapping
    ApiResponse<FileUploadResult> upload(@RequestPart MultipartFile file,
                                         @RequestParam(required = false) Integer width,
                                         @RequestParam(required = false) Integer height,
                                         @RequestParam(required = false) Integer durationSeconds) throws IOException {
        byte[] bytes = file.getBytes();
        String contentType = normalizeContentType(file.getContentType());
        validateUpload(file.getOriginalFilename(), contentType, bytes);
        ensureBucket();
        var objectKey = "uploads/" + UUID.randomUUID() + "-" + sanitizeFilename(file.getOriginalFilename());
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
        String url = publicBaseUrl.replaceAll("/$", "") + "/" + objectKey;
        return ApiResponse.ok(mediaRepository.save(objectKey, url, contentType, file.getSize(), width, height, durationSeconds));
    }

    private void validateUpload(String filename, String contentType, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "文件为空或超过上传大小限制");
        }
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "仅允许上传 png、jpeg、webp 图片或 mp4 视频");
        }
        String extension = extension(filename);
        if (!ALLOWED_EXTENSIONS.get(contentType).contains(extension)) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "文件扩展名与 MIME 类型不匹配");
        }
        if (!magicMatches(contentType, bytes)) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "文件头校验失败，疑似伪造上传类型");
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
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.bin";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void ensureBucket() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
        }
    }
}
