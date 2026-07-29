package io.github.devcircle.file.api;

import java.time.Instant;
import java.util.Map;

public record UploadIntentResult(
        Long mediaId,
        String objectKey,
        String uploadUrl,
        Map<String, String> requiredHeaders,
        Instant expiresAt
) {
}
