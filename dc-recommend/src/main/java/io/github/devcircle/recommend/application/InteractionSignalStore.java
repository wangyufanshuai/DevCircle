package io.github.devcircle.recommend.application;

interface InteractionSignalStore {
    void apply(String eventId, long actorId, long postId, String interactionType);
}
