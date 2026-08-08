package io.github.devcircle.recommend.model;

import java.util.List;

public record ExposureRequest(Long userId, List<Long> postIds, String scene) {
}
