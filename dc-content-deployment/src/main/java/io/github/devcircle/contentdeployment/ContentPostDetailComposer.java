package io.github.devcircle.contentdeployment;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.PostDetailComposer;
import io.github.devcircle.content.api.PostDetailView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Composes adjacent user and interaction state without moving domain ownership. */
final class ContentPostDetailComposer implements PostDetailComposer {
    private final RestClient userClient;
    private final RestClient interactionClient;
    private final Executor executor;

    @Autowired
    ContentPostDetailComposer(
            RestClient.Builder restClientBuilder,
            @Value("${devcircle.internal.user-uri:http://localhost:8102}") String userUri,
            @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
            @Qualifier("contentCompositionExecutor") Executor executor) {
        this(restClientBuilder.clone().baseUrl(userUri).build(),
                restClientBuilder.clone().baseUrl(interactionUri).build(), executor);
    }

    ContentPostDetailComposer(RestClient userClient, RestClient interactionClient, Executor executor) {
        this.userClient = userClient;
        this.interactionClient = interactionClient;
        this.executor = executor;
    }

    @Override
    public PostDetailView compose(PostDetailView detail, Long viewerId, String traceId) {
        long safeViewerId = viewerId == null || viewerId < 1 ? 0 : viewerId;
        long authorId = detail.author().id();
        CompletableFuture<JsonNode> profile = asyncGet(userClient, "/api/users/" + authorId, safeViewerId, traceId);
        CompletableFuture<JsonNode> interaction = safeViewerId == 0
                ? CompletableFuture.completedFuture(JsonNodeFactory.instance.objectNode())
                : asyncGet(interactionClient, "/api/posts/" + detail.id() + "/interaction-state", safeViewerId, traceId);
        CompletableFuture<JsonNode> follow = safeViewerId == 0
                ? CompletableFuture.completedFuture(JsonNodeFactory.instance.objectNode())
                : asyncGet(userClient, "/api/users/" + authorId + "/follow-state", safeViewerId, traceId);

        JsonNode profileData = join(profile);
        JsonNode interactionData = join(interaction);
        JsonNode followData = join(follow);
        PostDetailView.AuthorView author = new PostDetailView.AuthorView(
                authorId,
                requiredText(profileData, "username"),
                nullableText(profileData, "avatarUrl"),
                nullableText(profileData, "bio"),
                profileData.path("followers").asLong(0),
                followData.path("following").asBoolean(false));
        PostDetailView.ViewerState viewer = new PostDetailView.ViewerState(
                interactionData.path("liked").asBoolean(false),
                interactionData.path("favorited").asBoolean(false),
                detail.viewerState().readingProgress(),
                detail.viewerState().readingAnchor(),
                detail.viewerState().readingCompleted());
        long likes = interactionData.path("likeCount").isNumber()
                ? interactionData.path("likeCount").asLong()
                : detail.likes();
        return new PostDetailView(
                detail.id(), detail.type(), detail.theme(), detail.contentKind(), detail.difficulty(),
                detail.solvable(), detail.solvedCount(), detail.title(), detail.excerpt(), detail.markdown(),
                author, detail.tags(), detail.media(), likes, detail.comments(), detail.views(), detail.status(),
                detail.createdAt(), detail.clues(), detail.suspects(), detail.timeline(), detail.question(),
                detail.worldbuilding(), detail.cosmicFacts(), detail.relatedPostIds(), viewer,
                detail.deductionState(), detail.solution());
    }

    private CompletableFuture<JsonNode> asyncGet(RestClient client, String path, long viewerId, String traceId) {
        return CompletableFuture.supplyAsync(() -> get(client, path, viewerId, traceId), executor);
    }

    private JsonNode get(RestClient client, String path, long viewerId, String traceId) {
        try {
            RestClient.RequestHeadersSpec<?> request = client.get().uri(path)
                    .header("X-User-Id", Long.toString(viewerId));
            if (traceId != null && !traceId.isBlank()) request.header("X-Trace-Id", traceId);
            JsonNode body = request.retrieve().body(JsonNode.class);
            if (body != null && "OK".equals(body.path("code").stringValue())
                    && !body.path("data").isMissingNode() && !body.path("data").isNull()) {
                return body.path("data");
            }
            throw dependency();
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            BusinessException failure = dependency();
            failure.initCause(error);
            throw failure;
        }
    }

    private JsonNode join(CompletableFuture<JsonNode> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof BusinessException business) throw business;
            throw dependency();
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw dependency();
        return value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.stringValue();
    }

    private BusinessException dependency() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "作者与互动状态暂时无法加载，请稍后重试");
    }
}
