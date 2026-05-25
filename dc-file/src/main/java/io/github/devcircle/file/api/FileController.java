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
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
class FileController {
    private static final Set<String> ALLOWED = Set.of("image/png", "image/jpeg", "image/webp", "video/mp4");

    private final S3Client s3Client;
    private final MediaRepository mediaRepository;
    private final String bucket;
    private final String publicBaseUrl;

    FileController(S3Client s3Client,
                   MediaRepository mediaRepository,
                   @Value("${devcircle.s3.bucket}") String bucket,
                   @Value("${devcircle.s3.public-base-url}") String publicBaseUrl) {
        this.s3Client = s3Client;
        this.mediaRepository = mediaRepository;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostMapping
    ApiResponse<FileUploadResult> upload(@RequestPart MultipartFile file,
                                         @RequestParam(required = false) Integer width,
                                         @RequestParam(required = false) Integer height,
                                         @RequestParam(required = false) Integer durationSeconds) throws IOException {
        String contentType = file.getContentType();
        if (!ALLOWED.contains(contentType)) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "仅允许上传 png、jpeg、webp 图片或 mp4 视频");
        }
        ensureBucket();
        var objectKey = "uploads/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(file.getBytes()));
        String url = publicBaseUrl.replaceAll("/$", "") + "/" + objectKey;
        return ApiResponse.ok(mediaRepository.save(objectKey, url, contentType, file.getSize(), width, height, durationSeconds));
    }

    private void ensureBucket() {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
        }
    }
}
