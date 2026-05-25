package io.github.devcircle.admin.api;

import java.util.Map;

public record AdminActionResult(String action, Map<String, Object> attributes) {
}

