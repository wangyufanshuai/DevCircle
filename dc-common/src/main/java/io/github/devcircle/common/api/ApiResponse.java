package io.github.devcircle.common.api;

import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;

public record ApiResponse<T>(String code,
                             String message,
                             T data,
                             Map<String, Object> meta,
                             String traceId,
                             Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, Map.of());
    }

    public static <T> ApiResponse<T> ok(T data, Map<String, Object> meta) {
        return new ApiResponse<>("OK", "success", data, meta == null ? Map.of() : Map.copyOf(meta), currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static ApiResponse<Void> fail(String code, String message) {
        return fail(code, message, Map.of());
    }

    public static ApiResponse<Void> fail(String code, String message, Map<String, Object> meta) {
        return new ApiResponse<>(code, message, null, meta == null ? Map.of() : Map.copyOf(meta), currentTraceId(), Instant.now());
    }

    private static String currentTraceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? null : value;
    }
}
