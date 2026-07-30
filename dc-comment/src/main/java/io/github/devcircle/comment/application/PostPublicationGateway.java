package io.github.devcircle.comment.application;

public interface PostPublicationGateway {
    void requirePublished(Long postId);
}
