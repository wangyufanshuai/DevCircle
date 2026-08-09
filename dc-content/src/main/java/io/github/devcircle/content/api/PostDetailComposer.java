package io.github.devcircle.content.api;

/** Enriches a content-owned detail with viewer-facing data from adjacent domains. */
@FunctionalInterface
public interface PostDetailComposer {
    PostDetailView compose(PostDetailView detail, Long viewerId, String traceId);

    static PostDetailComposer passthrough() {
        return (detail, viewerId, traceId) -> detail;
    }
}
