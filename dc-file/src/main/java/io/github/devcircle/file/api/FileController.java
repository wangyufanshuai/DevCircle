package io.github.devcircle.file.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
class FileController {
    private static final Set<String> ALLOWED = Set.of("image/png", "image/jpeg", "image/webp");

    @PostMapping
    ApiResponse<FileUploadResult> upload(@RequestPart MultipartFile file) {
        if (!ALLOWED.contains(file.getContentType())) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "仅允许上传 png、jpeg、webp 图片");
        }
        var objectKey = "uploads/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
        return ApiResponse.ok(new FileUploadResult(objectKey, "http://localhost:9000/devcircle/" + objectKey, file.getSize()));
    }

    record FileUploadResult(String objectKey, String url, long size) {
    }
}

