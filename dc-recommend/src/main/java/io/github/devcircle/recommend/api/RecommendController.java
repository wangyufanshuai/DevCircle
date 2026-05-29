package io.github.devcircle.recommend.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.recommend.application.FeedService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/recommend")
class RecommendController {
    private final FeedService feedService;

    RecommendController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/feed")
    ApiResponse<CursorPage<FeedItem>> feed(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "10") int size,
                                           @RequestParam(defaultValue = "home") String scene) {
        return ApiResponse.ok(feedService.feed(userId, cursor, Math.min(size, 50), scene));
    }

    @PostMapping("/exposures")
    ApiResponse<ExposureResult> exposures(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                          @RequestBody ExposureRequest request) {
        ExposureRequest normalized = new ExposureRequest(userId, request.postIds(), request.scene());
        return ApiResponse.ok(new ExposureResult(feedService.recordExposure(normalized)));
    }

    @PostMapping("/events")
    ApiResponse<Map<String, Object>> events(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                            @RequestBody RecommendEventRequest request) {
        return ApiResponse.ok(feedService.recordRecommendEvent(userId, request.postId(), request.eventType(), request.scene(), request.experimentId(), request.bucket(), request.requestId()));
    }

    record ExposureResult(int recorded) {
    }

    record RecommendEventRequest(Long postId, String eventType, String scene, String experimentId, String bucket, String requestId) {
    }
}
