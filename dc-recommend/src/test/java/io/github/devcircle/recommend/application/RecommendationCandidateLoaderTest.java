package io.github.devcircle.recommend.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.contract.PostSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecommendationCandidateLoaderTest {
    @Test
    void loadsAValidContentPageWithTheResolvedThemeFilter() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://content.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationCandidateLoader loader = new RecommendationCandidateLoader(
                builder.build(), List.of(seedPost()));
        server.expect(requestTo("http://content.test/api/posts?page=1&size=20&theme=SCI_FI"))
                .andRespond(withSuccess(validPage(), MediaType.APPLICATION_JSON));

        var result = loader.load("SCI_FI", 20, false);

        assertThat(result.source()).isEqualTo("DB");
        assertThat(result.degraded()).isFalse();
        assertThat(result.posts()).extracting(PostSummary::id).containsExactly(7L);
        server.verify();
    }

    @Test
    void invalidDownstreamEnvelopeFailsStrictMode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://content.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationCandidateLoader loader = new RecommendationCandidateLoader(
                builder.build(), List.of(seedPost()));
        server.expect(requestTo("http://content.test/api/posts?page=1&size=10"))
                .andRespond(withSuccess("{\"code\":\"OK\",\"data\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> loader.load(null, 10, false))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                });
        server.verify();
    }

    @Test
    void dependencyFailureUsesSeedOnlyWhenExplicitlyEnabled() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://content.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PostSummary seed = seedPost();
        RecommendationCandidateLoader loader = new RecommendationCandidateLoader(
                builder.build(), List.of(seed));
        server.expect(requestTo("http://content.test/api/posts?page=1&size=10"))
                .andRespond(request -> { throw new IllegalStateException("content down"); });

        var result = loader.load(null, 10, true);

        assertThat(result.source()).isEqualTo("SEED_FALLBACK");
        assertThat(result.degraded()).isTrue();
        assertThat(result.posts()).containsExactly(seed);
        server.verify();
    }

    private String validPage() {
        return """
                {
                  "code": "OK",
                  "message": "success",
                  "data": {
                    "records": [{
                      "id": 7,
                      "type": "TEXT",
                      "theme": "SCI_FI",
                      "contentKind": "CASE_FILE",
                      "difficulty": "MEDIUM",
                      "solvable": false,
                      "solvedCount": 0,
                      "title": "候选内容",
                      "excerpt": "摘要",
                      "author": "author",
                      "authorId": 9,
                      "tags": [],
                      "mediaAssets": [],
                      "likes": 0,
                      "comments": 0,
                      "views": 0,
                      "status": "PUBLISHED",
                      "createdAt": "2026-08-09T00:00:00Z",
                      "clues": [],
                      "suspects": [],
                      "timeline": [],
                      "question": "",
                      "worldbuilding": [],
                      "cosmicFacts": [],
                      "relatedPostIds": []
                    }],
                    "total": 1,
                    "page": 1,
                    "size": 20
                  },
                  "meta": {},
                  "traceId": "trace-test",
                  "timestamp": "2026-08-09T00:00:00Z"
                }
                """;
    }

    private PostSummary seedPost() {
        return new PostSummary(101L, "TEXT", "DEDUCTION", "CASE_FILE", "MEDIUM", false, 0,
                "Seed", "摘要", "seed", 3L, List.of(), List.of(), 0, 0, 0,
                "PUBLISHED", Instant.EPOCH);
    }
}
