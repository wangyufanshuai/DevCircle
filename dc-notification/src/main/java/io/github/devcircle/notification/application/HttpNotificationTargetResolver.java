package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class HttpNotificationTargetResolver implements NotificationTargetResolver {
    private final RestClient contentClient;
    private final ObjectMapper objectMapper;

    HttpNotificationTargetResolver(@Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl,
                                   ObjectMapper objectMapper) {
        this.contentClient = BoundedRestClientFactory.create(contentBaseUrl);
        this.objectMapper = objectMapper;
    }

    @Override
    public long postAuthorId(long postId) {
        try {
            String body = contentClient.get().uri("/api/posts/{id}", postId).retrieve().body(String.class);
            JsonNode envelope = objectMapper.readTree(body);
            if (!"OK".equals(envelope.path("code").asText())) throw unavailable();
            long authorId = envelope.path("data").path("author").path("id").asLong(0);
            if (authorId <= 0) throw unavailable();
            return authorId;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "通知目标暂时无法解析");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "通知目标暂时无法解析");
    }
}
