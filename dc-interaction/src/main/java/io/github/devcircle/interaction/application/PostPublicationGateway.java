package io.github.devcircle.interaction.application;

public interface PostPublicationGateway {
    PublishedPost requirePublished(Long postId);

    record PublishedPost(Long postId, long likeCount, long authorId) {
    }
}
