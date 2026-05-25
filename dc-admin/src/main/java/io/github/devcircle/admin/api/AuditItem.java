package io.github.devcircle.admin.api;

public record AuditItem(Long postId, String title, String status, String author) {
}

