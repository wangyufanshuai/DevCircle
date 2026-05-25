package io.github.devcircle.file.api;

public record FileUploadResult(
        Long mediaId,
        String objectKey,
        String url,
        String contentType,
        long size,
        Integer width,
        Integer height,
        Integer durationSeconds,
        String status
) {
}
