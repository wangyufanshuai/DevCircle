package io.github.devcircle.interaction.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.interaction.application.PostPublicationGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

@Component
class HttpPostPublicationGateway implements PostPublicationGateway {
    private final RestClient restClient;

    HttpPostPublicationGateway(@Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(trimSlash(contentUri))
                .build();
    }

    @Override
    public PublishedPost requirePublished(Long postId) {
        try {
            Map<?, ?> envelope = restClient.get()
                    .uri("/api/posts/{id}", postId)
                    .retrieve()
                    .body(Map.class);
            if (envelope == null || !"OK".equals(envelope.get("code")) || !(envelope.get("data") instanceof Map<?, ?> data)
                    || !"PUBLISHED".equals(data.get("status"))) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
            }
            Object likes = data.get("likes");
            long likeCount = likes instanceof Number number ? Math.max(0, number.longValue()) : 0;
            return new PublishedPost(postId, likeCount);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容状态暂时无法确认，请稍后重试");
        }
    }

    private static String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
