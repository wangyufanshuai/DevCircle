package io.github.devcircle.content.api;

public record PostMediaView(Long mediaId, String objectKey, String url, String contentType, long size,
                            Integer width, Integer height, Integer durationSeconds, String status,
                            String role, String altText, int sortOrder) {
}
