package io.github.devcircle.comment.infrastructure;

import io.github.devcircle.comment.application.PostPublicationGateway;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
class HttpPostPublicationGateway implements PostPublicationGateway {
    private final RestClient restClient;

    HttpPostPublicationGateway(@Value("${devcircle.internal.content-uri:${DEVCIRCLE_CONTENT_BASE_URL:http://localhost:8103}}") String contentUri) {
        this.restClient = BoundedRestClientFactory.create(contentUri);
    }

    @Override
    public void requirePublished(Long postId) {
        try {
            Map<?, ?> envelope = restClient.get().uri("/api/posts/{id}", postId)
                    .retrieve().body(Map.class);
            if (envelope == null || !"OK".equals(envelope.get("code"))
                    || !(envelope.get("data") instanceof Map<?, ?> data)
                    || !"PUBLISHED".equals(data.get("status"))) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
            }
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容状态暂时无法确认，请稍后重试");
        }
    }
}
