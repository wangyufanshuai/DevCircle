package io.github.devcircle.common.event;

public final class EventTopics {
    public static final String POST_CREATED = "post.created";
    public static final String POST_UPDATED = "post.updated";
    public static final String POST_DELETED = "post.deleted";
    public static final String COMMENT_CREATED = "comment.created";
    public static final String INTERACTION_CHANGED = "interaction.changed";
    public static final String NOTIFICATION_CREATED = "notification.created";
    public static final String AUDIT_COMPLETED = "audit.completed";

    private EventTopics() {
    }
}

