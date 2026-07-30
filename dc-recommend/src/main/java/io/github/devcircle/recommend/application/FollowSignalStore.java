package io.github.devcircle.recommend.application;

import java.util.Set;

interface FollowSignalStore {
    void apply(String eventId, long viewerId, long authorId, String status);

    Set<Long> followedAuthorIds(long viewerId);
}
