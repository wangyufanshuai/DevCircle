package io.github.devcircle.contentdeployment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class PostDetailFacade {
    private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String contentUri;
    private final String userUri;
    private final String interactionUri;
    private final String fileUri;

    PostDetailFacade(WebClient.Builder webClientBuilder,
                     ObjectMapper objectMapper,
                     @Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri,
                     @Value("${devcircle.internal.user-uri:http://localhost:8102}") String userUri,
                     @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
                     @Value("${devcircle.internal.file-uri:http://localhost:8109}") String fileUri) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.contentUri = trimSlash(contentUri);
        this.userUri = trimSlash(userUri);
        this.interactionUri = trimSlash(interactionUri);
        this.fileUri = trimSlash(fileUri);
    }

    Mono<ObjectNode> load(Long postId, Long viewerId) {
        long safeViewerId = viewerId == null || viewerId < 1 ? 0 : viewerId;
        return source(contentUri, "/api/posts/" + postId, safeViewerId, true,
                "内容详情暂时无法加载，请稍后重试").flatMap(rawDetail -> {
            ObjectNode detail = requireObject(rawDetail, "内容详情暂时无法加载，请稍后重试");
            long authorId = detail.path("author").path("id").asLong(0);
            if (authorId < 1) {
                return Mono.error(dependency("作者资料暂时无法加载，请稍后重试"));
            }

            List<Long> mediaIds = mediaIds(detail.path("media"));
            Mono<JsonNode> profile = source(userUri, "/api/users/" + authorId, safeViewerId, false,
                    "作者资料暂时无法加载，请稍后重试");
            Mono<JsonNode> interaction = safeViewerId > 0
                    ? source(interactionUri, "/api/posts/" + postId + "/interaction-state", safeViewerId, false,
                    "互动状态暂时无法加载，请稍后重试")
                    : Mono.just(objectMapper.createObjectNode().put("liked", false).put("favorited", false));
            Mono<JsonNode> follow = safeViewerId > 0
                    ? source(userUri, "/api/users/" + authorId + "/follow-state", safeViewerId, false,
                    "关注状态暂时无法加载，请稍后重试")
                    : Mono.just(objectMapper.createObjectNode().put("following", false));
            Mono<JsonNode> mediaAccess = mediaIds.isEmpty()
                    ? Mono.just(objectMapper.createArrayNode())
                    : source(fileUri, "/api/files/access?ids=" + join(mediaIds), safeViewerId, false,
                    "内容媒体暂时无法加载，请稍后重试");

            return Mono.zip(profile, interaction, follow, mediaAccess)
                    .map(values -> merge(detail, values.getT1(), values.getT2(), values.getT3(), values.getT4(), mediaIds));
        });
    }

    private ObjectNode merge(ObjectNode rawDetail, JsonNode profile, JsonNode interaction, JsonNode follow,
                             JsonNode mediaAccess, List<Long> expectedMediaIds) {
        ObjectNode detail = rawDetail.deepCopy();
        detail.remove("answerKeywords");
        if (!detail.path("deductionState").path("solutionUnlocked").asBoolean(false)) {
            detail.putNull("solution");
        }
        ObjectNode author = detail.path("author") instanceof ObjectNode value
                ? value : objectMapper.createObjectNode();
        author.put("username", profile.path("username").asText(""));
        putNullable(author, "avatarUrl", profile.get("avatarUrl"));
        putNullable(author, "bio", profile.get("bio"));
        author.put("followerCount", profile.path("followers").asLong(0));
        author.put("followedByViewer", follow.path("following").asBoolean(false));
        detail.set("author", author);

        ObjectNode viewerState = detail.path("viewerState") instanceof ObjectNode value
                ? value : objectMapper.createObjectNode();
        viewerState.put("liked", interaction.path("liked").asBoolean(false));
        viewerState.put("favorited", interaction.path("favorited").asBoolean(false));
        detail.set("viewerState", viewerState);

        Map<Long, String> accessByMediaId = new LinkedHashMap<>();
        if (mediaAccess.isArray()) {
            mediaAccess.forEach(item -> {
                long mediaId = item.path("mediaId").asLong(0);
                String url = item.path("url").asText("");
                if (mediaId > 0 && !url.isBlank()) accessByMediaId.put(mediaId, url);
            });
        }
        if (!expectedMediaIds.isEmpty() && !accessByMediaId.keySet().containsAll(expectedMediaIds)) {
            throw dependency("内容媒体暂时无法加载，请稍后重试");
        }
        if (detail.path("media") instanceof ArrayNode media) {
            media.forEach(item -> {
                if (item instanceof ObjectNode asset) {
                    asset.put("url", accessByMediaId.getOrDefault(asset.path("mediaId").asLong(), ""));
                }
            });
        }
        return detail;
    }

    private Mono<JsonNode> source(String baseUri, String path, long viewerId, boolean exposeNotFound,
                                  String failureMessage) {
        return webClient.get().uri(baseUri + path)
                .header("X-User-Id", Long.toString(viewerId))
                .exchangeToMono(response -> decode(response, exposeNotFound, failureMessage))
                .timeout(SOURCE_TIMEOUT)
                .onErrorMap(error -> !(error instanceof BusinessException), error -> dependency(failureMessage, error));
    }

    private Mono<JsonNode> decode(ClientResponse response, boolean exposeNotFound, String failureMessage) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(JsonNode.class)
                .flatMap(body -> {
                    String code = body.path("code").asText();
                    if (status.is2xxSuccessful() && "OK".equals(code) && !body.path("data").isMissingNode()
                            && !body.path("data").isNull()) {
                        return Mono.just(body.path("data"));
                    }
                    if (exposeNotFound && (status.value() == 404 || "RESOURCE_NOT_FOUND".equals(code))) {
                        return Mono.error(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布"));
                    }
                    return Mono.error(dependency(failureMessage));
                })
                .switchIfEmpty(Mono.error(dependency(failureMessage)));
    }

    private ObjectNode requireObject(JsonNode value, String failureMessage) {
        if (value instanceof ObjectNode object) return object;
        throw dependency(failureMessage);
    }

    private List<Long> mediaIds(JsonNode media) {
        if (!media.isArray()) return List.of();
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        media.forEach(item -> {
            long id = item.path("mediaId").asLong(0);
            if (id > 0 && !ids.contains(id)) ids.add(id);
        });
        return List.copyOf(ids);
    }

    private void putNullable(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) target.putNull(field);
        else target.put(field, value.asText());
    }

    private String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private BusinessException dependency(String message) {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }

    private BusinessException dependency(String message, Throwable cause) {
        BusinessException failure = dependency(message);
        failure.initCause(cause);
        return failure;
    }

    private static String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
