package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.seed.ThemeContentLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/** Loads recommendation candidates and owns the explicit development-only seed fallback. */
@Service
public class RecommendationCandidateLoader {
    private final RestClient contentClient;
    private final List<PostSummary> seedPosts;

    @Autowired
    public RecommendationCandidateLoader(
            @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl) {
        this(BoundedRestClientFactory.create(contentBaseUrl), ThemeContentLibrary.summaries());
    }

    RecommendationCandidateLoader(RestClient contentClient, List<PostSummary> seedPosts) {
        this.contentClient = contentClient;
        this.seedPosts = List.copyOf(seedPosts);
    }

    public CandidateBatch load(String queryTheme, int size, boolean readFallbackEnabled) {
        try {
            ApiResponse<PageResponse<PostSummary>> response = contentClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/posts")
                            .queryParam("page", 1)
                            .queryParam("size", size)
                            .queryParamIfPresent("theme", Optional.ofNullable(queryTheme))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.data() == null || response.data().records() == null) {
                throw new IllegalStateException("Content service returned an invalid post page");
            }
            if (!response.data().records().isEmpty()) {
                return new CandidateBatch(response.data().records(), "DB", false);
            }
        } catch (RuntimeException ex) {
            if (!readFallbackEnabled) {
                throw dependency("内容服务暂时不可用", ex);
            }
            return seedFallback();
        }
        return readFallbackEnabled
                ? seedFallback()
                : new CandidateBatch(List.of(), "DB", false);
    }

    public List<PostSummary> seedPosts() {
        return seedPosts;
    }

    private CandidateBatch seedFallback() {
        return new CandidateBatch(seedPosts, "SEED_FALLBACK", true);
    }

    private BusinessException dependency(String message, RuntimeException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }

    public record CandidateBatch(List<PostSummary> posts, String source, boolean degraded) {
    }
}
