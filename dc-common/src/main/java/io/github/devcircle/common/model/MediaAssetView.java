package io.github.devcircle.common.model;

public record MediaAssetView(
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
