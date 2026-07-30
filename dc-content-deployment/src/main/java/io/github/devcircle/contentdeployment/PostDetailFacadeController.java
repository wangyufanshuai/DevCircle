package io.github.devcircle.contentdeployment;

import tools.jackson.databind.node.ObjectNode;
import io.github.devcircle.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/posts")
class PostDetailFacadeController {
    private final PostDetailFacade postDetailFacade;

    PostDetailFacadeController(PostDetailFacade postDetailFacade) {
        this.postDetailFacade = postDetailFacade;
    }

    @GetMapping("/{id:\\d+}")
    Mono<ApiResponse<ObjectNode>> detail(@PathVariable Long id,
                                         @RequestHeader(value = "X-User-Id", defaultValue = "0") Long viewerId) {
        return postDetailFacade.load(id, viewerId).map(ApiResponse::ok);
    }
}
